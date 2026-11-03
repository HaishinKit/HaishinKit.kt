#include "SRT/SrtSocket.h"
#include <arpa/inet.h>
#include <atomic>
#include <chrono>
#include <future>
#include <iostream>

using haishinkit::SrtEventLoop;
using haishinkit::SrtSocket;
using namespace std::chrono_literals;

namespace {
void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}

void await(std::future<void>& future) {
    require(future.wait_for(3s) == std::future_status::ready, "Readiness notification timed out");
    future.get();
}

std::future<void> wait(SrtSocket& socket, int events) {
    auto promise = std::make_shared<std::promise<void>>();
    auto future = promise->get_future();
    socket.Wait(events, [promise] { promise->set_value(); });
    return future;
}

void verifyReadinessBurst(const SrtSocket::Options& options, const sockaddr_storage& local) {
    std::vector<std::shared_ptr<SrtSocket>> senders, receivers;
    for (int i = 0; i < 96; ++i) {
        SrtSocket listener(options);
        listener.Listen(local);
        listener.BeginRead(true);
        auto sender = std::make_shared<SrtSocket>(options);
        sender->Connect(listener.GetLocalAddress(), nullptr, false);
        auto connected = wait(*sender, SRT_EPOLL_OUT);
        await(connected);
        require(sender->PollConnect(), "Burst sender failed to connect");
        auto accepted = wait(listener, SRT_EPOLL_IN);
        await(accepted);
        auto receiver = listener.Accept();
        require(receiver != nullptr, "Burst accept failed");
        receiver->BeginRead(false);
        senders.push_back(sender);
        receivers.push_back(receiver);
    }
    std::promise<void> entered, release;
    auto enteredFuture = entered.get_future();
    auto released = release.get_future().share();
    const auto gate = receivers.front()->Wait(0, [&entered, released] {
        entered.set_value();
        released.wait();
    });
    auto unblock = std::shared_ptr<void>(nullptr, [&release](void*) { release.set_value(); });
    SrtEventLoop::Shared().Signal(gate);
    await(enteredFuture);
    std::vector<std::future<void>> notifications;
    const char payload = 42;
    for (size_t i = 0; i < receivers.size(); ++i) {
        notifications.push_back(wait(*receivers[i], SRT_EPOLL_IN));
        require(senders[i]->TryBeginSend(), "Burst sender ownership failed");
        require(senders[i]->Send(&payload, 1) == 1, "Burst send failed");
        senders[i]->EndSend();
    }
    // Wait until every socket is readable while the loop is deliberately gated.
    const auto deadline = std::chrono::steady_clock::now() + 3s;
    for (auto& receiver : receivers) {
        while (receiver->GetPerformanceData().byteRcvBuf == 0) {
            require(std::chrono::steady_clock::now() < deadline, "Burst delivery timed out");
            std::this_thread::yield();
        }
    }
    unblock.reset();
    for (auto& notification : notifications) await(notification);
}

void run() {
    SrtSocket::Options options{0, 3000, {}, {}};
    SrtSocket listener(options), caller(options);
    sockaddr_storage local{};
    auto* ipv4 = reinterpret_cast<sockaddr_in*>(&local);
    ipv4->sin_family = AF_INET;
    inet_pton(AF_INET, "127.0.0.1", &ipv4->sin_addr);
    listener.Listen(local);
    listener.BeginRead(true);

    std::atomic<int> cancelledCallbacks{0};
    const auto cancelled = listener.Wait(SRT_EPOLL_IN, [&] { ++cancelledCallbacks; });
    listener.CancelWait(cancelled);
    caller.Connect(listener.GetLocalAddress(), nullptr, false);
    auto connecting = wait(caller, SRT_EPOLL_OUT);
    await(connecting);
    // The peer is already connected: accept readiness must survive a period
    // without an IN waiter, including a cancelled earlier registration.
    auto accepting = wait(listener, SRT_EPOLL_IN);
    await(accepting);
    require(caller.PollConnect(), "Connect readiness did not establish connection");
    auto receiver = listener.Accept();
    require(receiver != nullptr, "Accept readiness did not provide a socket");
    require(cancelledCallbacks == 0, "Cancelled registration was invoked");
    listener.EndRead();

    // OUT is one-shot even though the socket remains continuously writable.
    for (int i = 0; i < 100; ++i) {
        auto writable = wait(caller, SRT_EPOLL_OUT);
        await(writable);
    }

    require(caller.TryBeginSend(), "Could not reserve sender");
    auto sendSlot = wait(caller, 0);
    require(sendSlot.wait_for(0ms) == std::future_status::timeout, "Sender ownership wait resumed too early");
    caller.EndSend();
    await(sendSlot);

    receiver->BeginRead(false);
    auto readable = wait(*receiver, SRT_EPOLL_IN);
    require(caller.TryBeginSend(), "Could not reserve sender");
    const char payload = 42;
    require(caller.Send(&payload, 1) == 1, "Send failed");
    caller.EndSend();
    await(readable);
    std::vector<char> data;
    require(receiver->Receive(data) && data.size() == 1 && data[0] == payload, "Receive readiness lost data");

    auto closing = wait(*receiver, SRT_EPOLL_IN);
    auto disconnected = wait(caller, SRT_EPOLL_ERR);
    receiver->Close();
    await(closing);
    await(disconnected);
    receiver->EndRead();

    // Simulate libsrt retiring a peer before the wrapper receives its error.
    // Rearming this stale handle must not poison unrelated sockets' reactor.
    const auto stale = srt_create_socket();
    SrtEventLoop::Shared().WatchSocket(stale);
    srt_close(stale);
    auto retiredPromise = std::make_shared<std::promise<void>>();
    auto retired = retiredPromise->get_future();
    SrtEventLoop::Shared().Wait(stale, SRT_EPOLL_IN, [retiredPromise] { retiredPromise->set_value(); });
    await(retired);
    SrtSocket healthy(options);
    healthy.Listen(local);
    healthy.Close();

    verifyReadinessBurst(options, local);

    auto stopping = wait(listener, SRT_EPOLL_IN);
    SrtEventLoop::Shutdown();
    await(stopping);
    bool rejected = false;
    try { listener.Wait(SRT_EPOLL_IN, [] {}); }
    catch (const std::logic_error&) { rejected = true; }
    require(rejected, "Stopped loop accepted a new wait");
    caller.Close();
    listener.Close();
}
}

int main() {
    if (srt_startup() != 0) return 1;
    int result = 0;
    try {
        run();
        std::cout << "SRT epoll readiness, cancellation, close and shutdown passed\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        result = 1;
    }
    SrtEventLoop::Shutdown();
    srt_cleanup();
    return result;
}
