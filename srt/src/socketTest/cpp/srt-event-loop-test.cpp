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
