#pragma once

#include <srt.h>
#include "SrtEventLoop.h"
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace haishinkit {

class SrtSocketError : public std::runtime_error {
public:
    SrtSocketError(std::string message, int code, int reason);
    const int code;
    const int reason;
};

/** Live-mode socket implementation. No JVM references or blocking I/O waits. */
class SrtSocket {
public:
    struct Options {
        int latencyMs;
        int timeoutMs;
        std::string passphrase;
        std::string streamId;
    };

    static constexpr int PayloadSize = 1316;
    static constexpr int ReceiveSize = 1456;
    // Bound each native call so the coroutine can check cancellation between calls.
    static constexpr int SendBurstSize = PayloadSize * 32;

    explicit SrtSocket(const Options& options);
    ~SrtSocket();
    SrtSocket(const SrtSocket&) = delete;
    SrtSocket& operator=(const SrtSocket&) = delete;

    SRT_SOCKSTATUS GetStatus() const;
    sockaddr_storage GetLocalAddress() const;
    SRT_TRACEBSTATS GetPerformanceData(bool resetInterval = false) const;
    void Connect(const sockaddr_storage& remote, const sockaddr_storage* local, bool rendezvous);
    bool PollConnect();
    void Listen(const sockaddr_storage& local);
    void BeginRead(bool accepting);
    void EndRead();
    std::shared_ptr<SrtSocket> Accept();
    bool TryBeginSend();
    void EndSend();
    int Send(const char* data, int size);
    bool Receive(std::vector<char>& data);
    SrtEventLoop::Token Wait(int events, SrtEventLoop::Callback callback);
    void CancelWait(SrtEventLoop::Token token);
    void Close();

private:
    explicit SrtSocket(SRTSOCKET socket);
    void CheckOpen() const;
    void CheckConnected() const;
    void SetOption(SRT_SOCKOPT name, const void* value, int size);
    void ConfigureNonblocking();
    void CloseLocked();
    [[noreturn]] void Fail(const char* operation) const;
    static int AddressSize(const sockaddr_storage& address);

    mutable std::mutex mutex;
    SRTSOCKET socket = SRT_INVALID_SOCK;
    bool reading = false;
    bool sending = false;
    std::set<SrtEventLoop::Token> sendWaiters;
};

} // namespace haishinkit
