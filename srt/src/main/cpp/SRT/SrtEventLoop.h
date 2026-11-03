#pragma once

#include <srt.h>
#include <cstdint>
#include <exception>
#include <functional>
#include <map>
#include <mutex>
#include <set>
#include <thread>

namespace haishinkit {

/** Shared readiness loop. Callbacks always run outside its locks. */
class SrtEventLoop {
public:
    using Token = int64_t;
    using Callback = std::function<void()>;

    static SrtEventLoop& Shared();
    static void Shutdown();
    static void NotifyClose(SRTSOCKET socket);
    ~SrtEventLoop();
    void WatchSocket(SRTSOCKET socket);
    Token Wait(SRTSOCKET socket, int events, Callback callback);
    void Signal(Token token);
    void Cancel(Token token);
    void CloseSocket(SRTSOCKET socket);
    void Stop();

private:
    struct Waiter { SRTSOCKET socket; int events; Callback callback; };
    struct Watch { std::set<Token> tokens; int events = 0; };

    SrtEventLoop();
    void Refresh(SRTSOCKET socket, bool rearm = false);
    void Complete(Token token);
    void Wake();
    void Run();
    void RunLoop();

    std::mutex mutex;
    std::map<Token, Waiter> waiters;
    std::map<SRTSOCKET, Watch> watches;
    std::map<Token, Callback> ready;
    Token nextToken = 1;
    int epoll = -1;
    int wakeRead = -1;
    int wakeWrite = -1;
    bool stopping = false;
    std::exception_ptr failure;
    std::thread worker;
};

} // namespace haishinkit
