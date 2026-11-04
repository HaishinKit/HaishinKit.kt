#include "SrtEventLoop.h"
#include <udt.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <unistd.h>
#include <atomic>
#include <limits>
#include <stdexcept>
#include <string>
#include <utility>

namespace haishinkit {
namespace { std::atomic<SrtEventLoop*> activeLoop{nullptr}; }

void SrtEventLoop::Shutdown() {
    if (auto* loop = activeLoop.load()) loop->Stop();
}

void SrtEventLoop::NotifyClose(SRTSOCKET socket) {
    if (auto* loop = activeLoop.load()) loop->CloseSocket(socket);
}

SrtEventLoop& SrtEventLoop::Shared() {
    static SrtEventLoop loop;
    return loop;
}

SrtEventLoop::SrtEventLoop() {
    int descriptors[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, descriptors) != 0) throw std::runtime_error("SRT wake socket creation failed");
    wakeRead = descriptors[0];
    wakeWrite = descriptors[1];
    try {
        for (int fd : descriptors) {
            if (fcntl(fd, F_SETFL, O_NONBLOCK) == -1 || fcntl(fd, F_SETFD, FD_CLOEXEC) == -1) {
                throw std::runtime_error("SRT wake socket configuration failed");
            }
        }
#ifdef SO_NOSIGPIPE
        int enabled = 1;
        if (setsockopt(wakeWrite, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled)) != 0) {
            throw std::runtime_error("SRT wake socket configuration failed");
        }
#endif
        epoll = srt_epoll_create();
        const int events = SRT_EPOLL_IN | SRT_EPOLL_ERR;
        if (epoll < 0 || srt_epoll_add_ssock(epoll, wakeRead, &events) == SRT_ERROR) {
            throw std::runtime_error("SRT epoll creation failed");
        }
        worker = std::thread([this] { Run(); });
        activeLoop.store(this);
    } catch (...) {
        if (epoll >= 0) srt_epoll_release(epoll);
        close(wakeRead);
        close(wakeWrite);
        throw;
    }
}

SrtEventLoop::~SrtEventLoop() {
    activeLoop.store(nullptr);
    Stop();
    srt_epoll_release(epoll);
    close(wakeRead);
    close(wakeWrite);
}

void SrtEventLoop::WatchSocket(SRTSOCKET socket) {
    std::lock_guard<std::mutex> lock(mutex);
    if (failure) std::rethrow_exception(failure);
    if (stopping) throw std::logic_error("SRT event loop has stopped");
    if (watches.find(socket) != watches.end()) return;
    watches.emplace(socket, Watch{});
    try {
        Refresh(socket);
    } catch (...) {
        watches.erase(socket);
        throw;
    }
    Wake();
}

SrtEventLoop::Token SrtEventLoop::Wait(SRTSOCKET socket, int events, Callback callback) {
    std::lock_guard<std::mutex> lock(mutex);
    if (failure) std::rethrow_exception(failure);
    if (stopping) throw std::logic_error("SRT event loop has stopped");
    if (nextToken == std::numeric_limits<Token>::max()) throw std::overflow_error("SRT wait token limit reached");
    const auto token = nextToken++;
    waiters.emplace(token, Waiter{socket, events, std::move(callback)});
    try {
        watches[socket].tokens.insert(token);
        Refresh(socket, true);
    } catch (...) {
        waiters.erase(token);
        auto watch = watches.find(socket);
        if (watch != watches.end()) {
            watch->second.tokens.erase(token);
            if (watch->second.tokens.empty() && watch->second.events == 0) watches.erase(watch);
        }
        throw;
    }
    Wake();
    return token;
}

void SrtEventLoop::Refresh(SRTSOCKET socket, bool rearm) {
    auto watch = watches.find(socket);
    if (watch == watches.end()) return;
    // Keep the subscription for the socket's lifetime so SRT retains readiness
    // transitions even while no coroutine is waiting. In particular, addEPoll
    // does not seed accept readiness on an already-listening socket in 1.5.7.
    // ERR is edge-triggered to avoid spinning on an idle broken socket. IN/OUT
    // are armed only for one-shot waiters and re-armed from SRT's saved state.
    int events = SRT_EPOLL_ERR | SRT_EPOLL_ET;
    for (auto token : watch->second.tokens) events |= waiters.at(token).events;
    const int previous = watch->second.events;
    if (previous != events || rearm) {
        int result;
        if (previous == 0) result = srt_epoll_add_usock(epoll, socket, &events);
        else if (events == 0) result = srt_epoll_remove_usock(epoll, socket);
        else result = srt_epoll_update_usock(epoll, socket, &events);
        if (result == SRT_ERROR) {
            // libsrt can retire a disconnected handle before its owner closes it.
            // Wake this socket's waiters without failing the shared reactor.
            if (srt_getlasterror(nullptr) == SRT_EINVSOCK) {
                CloseSocketLocked(socket);
                return;
            }
            throw std::runtime_error(std::string("SRT epoll subscription failed: ") + srt_getlasterror_str());
        }
        watch->second.events = events;
    }

}

void SrtEventLoop::Complete(Token token) {
    const auto waiter = waiters.find(token);
    if (waiter == waiters.end()) return;
    const auto socket = waiter->second.socket;
    ready.emplace(token, std::move(waiter->second.callback));
    watches.at(socket).tokens.erase(token);
    waiters.erase(waiter);
    Refresh(socket);
}

void SrtEventLoop::Signal(Token token) {
    std::lock_guard<std::mutex> lock(mutex);
    Complete(token);
    Wake();
}

void SrtEventLoop::Cancel(Token token) {
    Callback removed;
    {
        std::lock_guard<std::mutex> lock(mutex);
        const auto waiter = waiters.find(token);
        if (waiter != waiters.end()) {
            const auto socket = waiter->second.socket;
            removed = std::move(waiter->second.callback);
            watches.at(socket).tokens.erase(token);
            waiters.erase(waiter);
            Refresh(socket);
        } else {
            const auto pending = ready.find(token);
            if (pending != ready.end()) {
                removed = std::move(pending->second);
                ready.erase(pending);
            }
        }
        Wake();
    }
}

void SrtEventLoop::CloseSocket(SRTSOCKET socket) {
    std::lock_guard<std::mutex> lock(mutex);
    CloseSocketLocked(socket);
    Wake();
}

void SrtEventLoop::CloseSocketLocked(SRTSOCKET socket) {
    const auto watch = watches.find(socket);
    if (watch == watches.end()) return;
    // Remove interest before the native handle can be closed or reused.
    if (watch->second.events != 0) srt_epoll_remove_usock(epoll, socket);
    for (auto token : watch->second.tokens) {
        auto waiter = waiters.find(token);
        ready.emplace(token, std::move(waiter->second.callback));
        waiters.erase(waiter);
    }
    watches.erase(watch);
}

void SrtEventLoop::Stop() {
    {
        std::lock_guard<std::mutex> lock(mutex);
        stopping = true;
        Wake();
    }
    if (worker.joinable()) worker.join();
}

void SrtEventLoop::Wake() {
    const char value = 1;
#ifdef MSG_NOSIGNAL
    send(wakeWrite, &value, 1, MSG_NOSIGNAL);
#else
    send(wakeWrite, &value, 1, 0);
#endif
    // EAGAIN means the wake socket is already readable.
}

void SrtEventLoop::Run() {
    try {
        RunLoop();
    } catch (...) {
        std::map<Token, Waiter> abandoned;
        std::map<Token, Callback> completed;
        {
            std::lock_guard<std::mutex> lock(mutex);
            failure = std::current_exception();
            stopping = true;
            abandoned.swap(waiters);
            completed.swap(ready);
            watches.clear();
        }
        for (auto& entry : abandoned) entry.second.callback();
        for (auto& entry : completed) entry.second();
    }
}

void SrtEventLoop::RunLoop() {
    while (true) {
        std::set<SRTSOCKET> readable;
        std::set<SRTSOCKET> writable;
        std::set<SYSSOCKET> systemRead;
        // The system wake socket permits registration, cancellation and shutdown
        // without polling timeouts. ERR is reported in both SRT arrays.
        // The C array wrapper truncates after consuming all ET notices. The C++
        // set API returns every event, including simultaneous bursts above 64.
        if (UDT::epoll_wait(epoll, &readable, &writable, -1, &systemRead, nullptr) == SRT_ERROR) {
            throw std::runtime_error(std::string("SRT epoll wait failed: ") + srt_getlasterror_str());
        }
        if (!systemRead.empty()) {
            char drain[256];
            while (recv(wakeRead, drain, sizeof(drain), 0) > 0) {}
        }
        std::map<SRTSOCKET, int> events;
        for (auto socket : readable) events[socket] |= SRT_EPOLL_IN;
        for (auto socket : writable) events[socket] |= SRT_EPOLL_OUT;
        std::map<Token, Callback> callbacks;
        bool stop;
        {
            std::lock_guard<std::mutex> lock(mutex);
            for (const auto& event : events) {
                int delivered = event.second;
                // srt_epoll_wait reports ERR through both readiness arrays,
                // even when the subscriber requested only errors.
                if ((delivered & (SRT_EPOLL_IN | SRT_EPOLL_OUT)) == (SRT_EPOLL_IN | SRT_EPOLL_OUT)) {
                    delivered |= SRT_EPOLL_ERR;
                }
                const auto watch = watches.find(event.first);
                if (watch == watches.end()) continue;
                const auto tokens = watch->second.tokens;
                for (auto token : tokens) {
                    const auto waiter = waiters.find(token);
                    if (waiter != waiters.end() && (waiter->second.events & delivered)) Complete(token);
                }
            }
            stop = stopping;
            if (stop) {
                for (auto& waiter : waiters) ready.emplace(waiter.first, std::move(waiter.second.callback));
                waiters.clear();
                watches.clear();
            }
            callbacks.swap(ready);
        }
        // A continuation may re-enter JNI here. Hold neither event-loop nor
        // socket locks while resuming it, including during shutdown.
        for (auto& callback : callbacks) callback.second();
        if (stop) return;
    }
}

} // namespace haishinkit
