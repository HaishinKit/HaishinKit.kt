#include "SrtSocket.h"
#include <algorithm>
#include <utility>

namespace haishinkit {

SrtSocketError::SrtSocketError(std::string message, int code, int reason)
    : std::runtime_error(std::move(message)), code(code), reason(reason) {}

SrtSocket::SrtSocket(const Options& options) {
    if (options.latencyMs < 0 || options.timeoutMs <= 0 ||
        (!options.passphrase.empty() && (options.passphrase.size() < 10 || options.passphrase.size() > 79)) ||
        options.streamId.size() > 512) {
        throw std::invalid_argument("Invalid SRT socket options");
    }
    socket = srt_create_socket();
    if (socket == SRT_INVALID_SOCK) Fail("create_socket");
    try {
        const SRT_TRANSTYPE type = SRTT_LIVE;
        const int payload = PayloadSize;
        SetOption(SRTO_TRANSTYPE, &type, sizeof(type));
        SetOption(SRTO_PAYLOADSIZE, &payload, sizeof(payload));
        SetOption(SRTO_LATENCY, &options.latencyMs, sizeof(options.latencyMs));
        SetOption(SRTO_CONNTIMEO, &options.timeoutMs, sizeof(options.timeoutMs));
        if (!options.passphrase.empty()) SetOption(SRTO_PASSPHRASE, options.passphrase.data(), options.passphrase.size());
        if (!options.streamId.empty()) SetOption(SRTO_STREAMID, options.streamId.data(), options.streamId.size());
        ConfigureNonblocking();
    } catch (...) {
        CloseLocked();
        throw;
    }
}

SrtSocket::SrtSocket(SRTSOCKET socket) : socket(socket) {}

SrtSocket::~SrtSocket() {
    // The last shared owner is gone, so no concurrent operation can remain.
    if (socket != SRT_INVALID_SOCK) {
        try { SrtEventLoop::NotifyClose(socket); } catch (...) {}
        srt_close(socket);
    }
}

SRT_SOCKSTATUS SrtSocket::GetStatus() const {
    std::lock_guard<std::mutex> lock(mutex);
    return socket == SRT_INVALID_SOCK ? SRTS_CLOSED : srt_getsockstate(socket);
}

sockaddr_storage SrtSocket::GetLocalAddress() const {
    std::lock_guard<std::mutex> lock(mutex);
    CheckOpen();
    sockaddr_storage address{};
    int size = sizeof(address);
    if (srt_getsockname(socket, reinterpret_cast<sockaddr*>(&address), &size) == SRT_ERROR) Fail("getsockname");
    return address;
}

SRT_TRACEBSTATS SrtSocket::GetPerformanceData() const {
    std::lock_guard<std::mutex> lock(mutex);
    CheckOpen();
    SRT_TRACEBSTATS result{};
    if (srt_bstats(socket, &result, 0) == SRT_ERROR) Fail("bstats");
    return result;
}

void SrtSocket::Connect(const sockaddr_storage& remote, const sockaddr_storage* local, bool rendezvous) {
    std::lock_guard<std::mutex> lock(mutex);
    CheckOpen();
    if (srt_getsockstate(socket) != SRTS_INIT) throw std::logic_error("Socket has already been opened");
    if (rendezvous && !local) throw std::invalid_argument("Rendezvous requires a local address");
    if (local && local->ss_family != remote.ss_family) throw std::invalid_argument("Address families must match");
    try {
        SrtEventLoop::Shared().WatchSocket(socket);
        if (rendezvous) {
            const bool enabled = true;
            SetOption(SRTO_RENDEZVOUS, &enabled, sizeof(enabled));
        }
        if (local && srt_bind(socket, reinterpret_cast<const sockaddr*>(local), AddressSize(*local)) == SRT_ERROR) Fail("bind");
        if (srt_connect(socket, reinterpret_cast<const sockaddr*>(&remote), AddressSize(remote)) == SRT_ERROR) Fail("connect");
    } catch (...) {
        CloseLocked();
        throw;
    }
}

bool SrtSocket::PollConnect() {
    std::lock_guard<std::mutex> lock(mutex);
    CheckOpen();
    const auto state = srt_getsockstate(socket);
    if (state == SRTS_CONNECTED) return true;
    if (state == SRTS_CONNECTING || state == SRTS_OPENED) return false;
    // Async connect failed on a native worker: only the socket's reject reason
    // is meaningful here, not this thread's last-error state.
    const auto error = SrtSocketError("SRT connection failed", SRT_ECONNSETUP, srt_getrejectreason(socket));
    CloseLocked();
    throw error;
}

void SrtSocket::Listen(const sockaddr_storage& local) {
    std::lock_guard<std::mutex> lock(mutex);
    CheckOpen();
    if (srt_getsockstate(socket) != SRTS_INIT) throw std::logic_error("Socket has already been opened");
    try {
        SrtEventLoop::Shared().WatchSocket(socket);
        if (srt_bind(socket, reinterpret_cast<const sockaddr*>(&local), AddressSize(local)) == SRT_ERROR) Fail("bind");
        if (srt_listen(socket, 1) == SRT_ERROR) Fail("listen");
    } catch (...) {
        CloseLocked();
        throw;
    }
}

void SrtSocket::BeginRead(bool accepting) {
    std::lock_guard<std::mutex> lock(mutex);
    CheckOpen();
    if (reading) throw std::logic_error("Socket already has an active reader");
    const auto state = srt_getsockstate(socket);
    if (state != (accepting ? SRTS_LISTENING : SRTS_CONNECTED)) throw std::logic_error("Socket is not in the required receive state");
    reading = true;
}

void SrtSocket::EndRead() {
    std::lock_guard<std::mutex> lock(mutex);
    reading = false;
}

std::shared_ptr<SrtSocket> SrtSocket::Accept() {
    std::lock_guard<std::mutex> lock(mutex);
    CheckOpen();
    if (!reading || srt_getsockstate(socket) != SRTS_LISTENING) throw std::logic_error("Socket is not accepting");
    const auto accepted = srt_accept(socket, nullptr, nullptr);
    if (accepted == SRT_INVALID_SOCK) {
        if (srt_getlasterror(nullptr) == SRT_EASYNCRCV) return nullptr;
        Fail("accept");
    }
    std::unique_ptr<SrtSocket> result;
    try {
        result.reset(new SrtSocket(accepted));
    } catch (...) {
        srt_close(accepted);
        throw;
    }
    result->ConfigureNonblocking();
    SrtEventLoop::Shared().WatchSocket(accepted);
    return std::shared_ptr<SrtSocket>(std::move(result));
}

bool SrtSocket::TryBeginSend() {
    std::lock_guard<std::mutex> lock(mutex);
    CheckConnected();
    if (sending) return false;
    sending = true;
    return true;
}

void SrtSocket::EndSend() {
    std::set<SrtEventLoop::Token> wake;
    {
        std::lock_guard<std::mutex> lock(mutex);
        sending = false;
        wake.swap(sendWaiters);
    }
    for (auto token : wake) SrtEventLoop::Shared().Signal(token);
}

int SrtSocket::Send(const char* data, int size) {
    std::lock_guard<std::mutex> lock(mutex);
    CheckConnected();
    if (!sending) throw std::logic_error("No active send operation");
    if (size < 0 || size > SendBurstSize) throw std::invalid_argument("Invalid send size");
    int offset = 0;
    while (offset < size) {
        const int length = std::min(PayloadSize, size - offset);
        const int sent = srt_sendmsg(socket, data + offset, length, -1, 0);
        if (sent == SRT_ERROR) {
            if (srt_getlasterror(nullptr) == SRT_EASYNCSND) return offset;
            Fail("sendmsg");
        }
        if (sent != length) throw std::runtime_error("Incomplete SRT message send");
        offset += sent;
    }
    return offset;
}

bool SrtSocket::Receive(std::vector<char>& data) {
    std::lock_guard<std::mutex> lock(mutex);
    CheckConnected();
    if (!reading) throw std::logic_error("No active receive operation");
    data.resize(ReceiveSize);
    const int size = srt_recvmsg(socket, data.data(), data.size());
    if (size == SRT_ERROR) {
        if (srt_getlasterror(nullptr) == SRT_EASYNCRCV) return false;
        Fail("recvmsg");
    }
    data.resize(size);
    return true;
}

SrtEventLoop::Token SrtSocket::Wait(int events, SrtEventLoop::Callback callback) {
    std::lock_guard<std::mutex> lock(mutex);
    CheckOpen();
    if (events != 0 && events != SRT_EPOLL_IN && events != SRT_EPOLL_OUT && events != SRT_EPOLL_ERR) {
        throw std::invalid_argument("Invalid readiness event");
    }
    auto& loop = SrtEventLoop::Shared();
    const auto token = loop.Wait(socket, events, std::move(callback));
    try {
        if (events == 0) {
            if (sending) sendWaiters.insert(token);
            else loop.Signal(token);
        }
    } catch (...) {
        loop.Cancel(token);
        throw;
    }
    return token;
}

void SrtSocket::CancelWait(SrtEventLoop::Token token) {
    std::lock_guard<std::mutex> lock(mutex);
    sendWaiters.erase(token);
    SrtEventLoop::Shared().Cancel(token);
}

void SrtSocket::Close() {

    std::lock_guard<std::mutex> lock(mutex);
    CloseLocked();
}

void SrtSocket::CheckOpen() const {
    if (socket == SRT_INVALID_SOCK) throw std::logic_error("Socket is closed");
}

void SrtSocket::CheckConnected() const {
    CheckOpen();
    const auto state = srt_getsockstate(socket);
    // Broken connections must retain native error/rejection information.
    if (state == SRTS_BROKEN || state == SRTS_CLOSING || state == SRTS_CLOSED || state == SRTS_NONEXIST) {
        throw SrtSocketError("SRT connection lost", SRT_ECONNLOST, srt_getrejectreason(socket));
    }
    if (state != SRTS_CONNECTED) throw std::logic_error("Socket is not connected");
}

void SrtSocket::SetOption(SRT_SOCKOPT name, const void* value, int size) {
    if (srt_setsockflag(socket, name, value, size) == SRT_ERROR) Fail("setsockflag");
}

void SrtSocket::ConfigureNonblocking() {
    const bool sync = false;
    const linger noLinger = {0, 0};
    SetOption(SRTO_SNDSYN, &sync, sizeof(sync));
    SetOption(SRTO_RCVSYN, &sync, sizeof(sync));
    SetOption(SRTO_LINGER, &noLinger, sizeof(noLinger));
}

void SrtSocket::CloseLocked() {
    if (socket != SRT_INVALID_SOCK) {
        const auto previous = socket;
        socket = SRT_INVALID_SOCK;
        try {
            SrtEventLoop::NotifyClose(previous);
        } catch (...) {
            srt_close(previous);
            throw;
        }
        sendWaiters.clear();
        srt_close(previous);
    }
}

void SrtSocket::Fail(const char* operation) const {
    const int code = srt_getlasterror(nullptr);
    const std::string message = std::string(operation) + ": " + srt_getlasterror_str();
    throw SrtSocketError(message, code, srt_getrejectreason(socket));
}

int SrtSocket::AddressSize(const sockaddr_storage& address) {
    if (address.ss_family == AF_INET) return sizeof(sockaddr_in);
    if (address.ss_family == AF_INET6) return sizeof(sockaddr_in6);
    throw std::invalid_argument("Invalid address family");
}

} // namespace haishinkit
