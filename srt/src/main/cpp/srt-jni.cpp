#include "SRT/SrtSocket.h"
#include <jni.h>
#include <arpa/inet.h>
#include <algorithm>
#include <array>
#include <limits>
#include <map>

namespace {
using haishinkit::SrtSocket;
using haishinkit::SrtSocketError;

// Opaque IDs, rather than Java object references or raw pointers. An in-flight
// JNI call retains a shared owner even if another thread closes the Kotlin wrapper.
std::mutex contextsMutex;
std::map<jlong, std::shared_ptr<SrtSocket>> contexts;
jlong nextContext = 1;

jlong retain(std::shared_ptr<SrtSocket> socket) {
    std::lock_guard<std::mutex> lock(contextsMutex);
    if (nextContext == std::numeric_limits<jlong>::max()) throw std::overflow_error("Socket context limit reached");
    const auto context = nextContext++;
    contexts.emplace(context, std::move(socket));
    return context;
}

std::shared_ptr<SrtSocket> find(jlong context, bool required = true) {
    std::lock_guard<std::mutex> lock(contextsMutex);
    const auto entry = contexts.find(context);
    if (entry != contexts.end()) return entry->second;
    if (required) throw std::logic_error("Socket is closed");
    return nullptr;
}

void release(jlong context) {
    std::shared_ptr<SrtSocket> socket;
    {
        std::lock_guard<std::mutex> lock(contextsMutex);
        const auto entry = contexts.find(context);
        if (entry == contexts.end()) return;
        socket = std::move(entry->second);
        contexts.erase(entry);
    }
    socket->Close();
}

void throwJava(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    const auto clazz = env->FindClass(type);
    if (clazz) env->ThrowNew(clazz, message);
}

void throwJava(JNIEnv* env, const SrtSocketError& error) {
    if (env->ExceptionCheck()) return;
    const auto type = env->FindClass("com/haishinkit/srt/SrtSocketException");
    if (!type) return;
    const auto constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;II)V");
    if (!constructor) return;
    const auto text = env->NewStringUTF(error.what());
    if (!text) return;
    const auto exception = static_cast<jthrowable>(env->NewObject(type, constructor, text, error.code, error.reason));
    if (exception) env->Throw(exception);
}

// Never allow a C++ exception to cross JNI, including allocation failures.
template<typename R, typename F>
R call(JNIEnv* env, R fallback, F block) {
    try {
        return block();
    } catch (const SrtSocketError& error) {
        throwJava(env, error);
    } catch (const std::invalid_argument& error) {
        throwJava(env, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::logic_error& error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Native SRT allocation failed");
    } catch (const std::exception& error) {
        throwJava(env, "java/io/IOException", error.what());
    } catch (...) {
        throwJava(env, "java/io/IOException", "Native SRT operation failed");
    }
    return fallback;
}

template<typename F>
void call(JNIEnv* env, F block) {
    call(env, 0, [&] { block(); return 0; });
}

// Event callbacks originate on the shared native thread, not a JNI caller's
// thread. Never retain a JNIEnv; attach only for the callback/ref cleanup.
class JvmEnvironment {
public:
    explicit JvmEnvironment(JavaVM* vm) : vm(vm) {
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            attached = vm->AttachCurrentThreadAsDaemon(&env, nullptr) == JNI_OK;
            if (!attached) env = nullptr;
        }
    }
    ~JvmEnvironment() { if (attached) vm->DetachCurrentThread(); }
    JNIEnv* env = nullptr;
private:
    JavaVM* vm;
    bool attached = false;
};

class JavaWaiter {
public:
    JavaWaiter(JNIEnv* env, jobject target) {
        if (env->GetJavaVM(&vm) != JNI_OK) throw std::runtime_error("Java VM is unavailable");
        const auto type = env->GetObjectClass(target);
        if (!type) throw std::runtime_error("Event waiter class is unavailable");
        onReady = env->GetMethodID(type, "onReady", "()V");
        env->DeleteLocalRef(type);
        if (!onReady) throw std::runtime_error("Event waiter callback is unavailable");
        callback = env->NewGlobalRef(target);
        if (!callback) throw std::bad_alloc();
    }
    ~JavaWaiter() {
        JvmEnvironment context(vm);
        if (context.env) context.env->DeleteGlobalRef(callback);
    }
    void Complete() noexcept {
        JvmEnvironment context(vm);
        if (!context.env) return;
        context.env->CallVoidMethod(callback, onReady);
        // onReady only resumes a continuation; an exception here indicates a
        // broken callback contract and must not poison the native worker thread.
        if (context.env->ExceptionCheck()) {
            context.env->ExceptionDescribe();
            context.env->ExceptionClear();
        }
    }
private:
    JavaVM* vm = nullptr;
    jobject callback = nullptr;
    jmethodID onReady = nullptr;
};

std::string bytes(JNIEnv* env, jbyteArray array) {

    if (!array) return {};
    std::string value(env->GetArrayLength(array), '\0');
    env->GetByteArrayRegion(array, 0, value.size(), reinterpret_cast<jbyte*>(value.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Unable to read byte array");
    return value;
}

sockaddr_storage address(JNIEnv* env, jbyteArray array, jint port) {
    if (!array || port < 0 || port > 65535) throw std::invalid_argument("Invalid socket address");
    const auto value = bytes(env, array);
    sockaddr_storage result{};
    if (value.size() == 4) {
        auto* v4 = reinterpret_cast<sockaddr_in*>(&result);
        v4->sin_family = AF_INET;
        v4->sin_port = htons(static_cast<uint16_t>(port));
        std::copy(value.begin(), value.end(), reinterpret_cast<char*>(&v4->sin_addr));
    } else if (value.size() == 16) {
        auto* v6 = reinterpret_cast<sockaddr_in6*>(&result);
        v6->sin6_family = AF_INET6;
        v6->sin6_port = htons(static_cast<uint16_t>(port));
        std::copy(value.begin(), value.end(), reinterpret_cast<char*>(&v6->sin6_addr));
    } else {
        throw std::invalid_argument("Invalid IP address length");
    }
    return result;
}

jobject javaAddress(JNIEnv* env, const sockaddr_storage& address) {
    const bool ipv6 = address.ss_family == AF_INET6;
    const auto* v4 = reinterpret_cast<const sockaddr_in*>(&address);
    const auto* v6 = reinterpret_cast<const sockaddr_in6*>(&address);
    const auto array = env->NewByteArray(ipv6 ? 16 : 4);
    if (!array) return nullptr;
    env->SetByteArrayRegion(array, 0, ipv6 ? 16 : 4,
        reinterpret_cast<const jbyte*>(ipv6 ? static_cast<const void*>(&v6->sin6_addr) : &v4->sin_addr));
    const auto type = env->FindClass("java/net/InetAddress");
    if (!type) return nullptr;
    const auto getAddress = env->GetStaticMethodID(type, "getByAddress", "([B)Ljava/net/InetAddress;");
    if (!getAddress) return nullptr;
    const auto ip = env->CallStaticObjectMethod(type, getAddress, array);
    if (env->ExceptionCheck()) return nullptr;
    const auto socketType = env->FindClass("java/net/InetSocketAddress");
    if (!socketType) return nullptr;
    const auto constructor = env->GetMethodID(socketType, "<init>", "(Ljava/net/InetAddress;I)V");
    if (!constructor) return nullptr;
    return env->NewObject(socketType, constructor, ip, static_cast<jint>(ntohs(ipv6 ? v6->sin6_port : v4->sin_port)));
}
} // namespace

extern "C" {
JNIEXPORT jstring JNICALL Java_com_haishinkit_srt_SrtSocket_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(SRT_VERSION_STRING);
}

JNIEXPORT jlong JNICALL Java_com_haishinkit_srt_SrtSocket_nativeCreate(
    JNIEnv* env, jclass, jint latency, jint timeout, jbyteArray passphrase, jbyteArray streamId) {
    return call(env, jlong{0}, [&] {
        const SrtSocket::Options options{latency, timeout, bytes(env, passphrase), bytes(env, streamId)};
        return retain(std::make_shared<SrtSocket>(options));
    });
}

JNIEXPORT jint JNICALL Java_com_haishinkit_srt_SrtSocket_nativeStatus(JNIEnv* env, jobject, jlong context) {
    return call(env, jint{SRTS_CLOSED}, [&] {
        const auto socket = find(context, false);
        return static_cast<jint>(socket ? socket->GetStatus() : SRTS_CLOSED);
    });
}

JNIEXPORT void JNICALL Java_com_haishinkit_srt_SrtSocket_nativeConnect(
    JNIEnv* env, jobject, jlong context, jbyteArray remoteBytes, jint remotePort,
    jbyteArray localBytes, jint localPort, jboolean rendezvous) {
    call(env, [&] {
        if (remotePort <= 0) throw std::invalid_argument("Remote port must be greater than zero");
        const auto remote = address(env, remoteBytes, remotePort);
        const auto local = localBytes ? address(env, localBytes, localPort) : sockaddr_storage{};
        const auto socket = find(context);
        try {
            socket->Connect(remote, localBytes ? &local : nullptr, rendezvous);
        } catch (...) {
            if (socket->GetStatus() == SRTS_CLOSED) release(context);
            throw;
        }

    });
}

JNIEXPORT jboolean JNICALL Java_com_haishinkit_srt_SrtSocket_nativePollConnect(JNIEnv* env, jobject, jlong context) {
    return call(env, jboolean{false}, [&] { return static_cast<jboolean>(find(context)->PollConnect()); });
}

JNIEXPORT void JNICALL Java_com_haishinkit_srt_SrtSocket_nativeListen(
    JNIEnv* env, jobject, jlong context, jbyteArray localBytes, jint port) {
    call(env, [&] {
        const auto socket = find(context);
        try {
            socket->Listen(address(env, localBytes, port));
        } catch (...) {
            if (socket->GetStatus() == SRTS_CLOSED) release(context);
            throw;
        }
    });
}

JNIEXPORT void JNICALL Java_com_haishinkit_srt_SrtSocket_nativeBeginRead(JNIEnv* env, jobject, jlong context, jboolean accepting) {
    call(env, [&] { find(context)->BeginRead(accepting); });
}

JNIEXPORT void JNICALL Java_com_haishinkit_srt_SrtSocket_nativeEndRead(JNIEnv* env, jobject, jlong context) {
    call(env, [&] { const auto socket = find(context, false); if (socket) socket->EndRead(); });
}

JNIEXPORT jlong JNICALL Java_com_haishinkit_srt_SrtSocket_nativeAccept(JNIEnv* env, jobject, jlong context) {
    return call(env, jlong{0}, [&] {
        const auto accepted = find(context)->Accept();
        return accepted ? retain(accepted) : jlong{0};
    });
}

JNIEXPORT jboolean JNICALL Java_com_haishinkit_srt_SrtSocket_nativeTryBeginSend(JNIEnv* env, jobject, jlong context) {
    return call(env, jboolean{false}, [&] { return static_cast<jboolean>(find(context)->TryBeginSend()); });
}

JNIEXPORT void JNICALL Java_com_haishinkit_srt_SrtSocket_nativeEndSend(JNIEnv* env, jobject, jlong context) {
    call(env, [&] { const auto socket = find(context, false); if (socket) socket->EndSend(); });
}

JNIEXPORT jint JNICALL Java_com_haishinkit_srt_SrtSocket_nativeSend(
    JNIEnv* env, jobject, jlong context, jbyteArray data, jint offset) {
    return call(env, jint{0}, [&] {
        if (!data || offset < 0 || offset > env->GetArrayLength(data)) throw std::invalid_argument("Invalid send range");
        const int size = std::min(env->GetArrayLength(data) - offset, SrtSocket::SendBurstSize);
        std::array<jbyte, SrtSocket::SendBurstSize> buffer{};
        env->GetByteArrayRegion(data, offset, size, buffer.data());
        if (env->ExceptionCheck()) return jint{0};
        return static_cast<jint>(find(context)->Send(reinterpret_cast<const char*>(buffer.data()), size));
    });
}

JNIEXPORT jbyteArray JNICALL Java_com_haishinkit_srt_SrtSocket_nativeReceive(JNIEnv* env, jobject, jlong context) {
    return call(env, jbyteArray{nullptr}, [&]() -> jbyteArray {
        std::vector<char> data;
        if (!find(context)->Receive(data)) return nullptr;
        const auto array = env->NewByteArray(data.size());
        if (array) env->SetByteArrayRegion(array, 0, data.size(), reinterpret_cast<const jbyte*>(data.data()));
        return array;
    });
}

JNIEXPORT jobject JNICALL Java_com_haishinkit_srt_SrtSocket_nativeLocalAddress(JNIEnv* env, jobject, jlong context) {
    return call(env, jobject{nullptr}, [&] { return javaAddress(env, find(context)->GetLocalAddress()); });
}

JNIEXPORT jobject JNICALL Java_com_haishinkit_srt_SrtSocket_nativePerformanceData(JNIEnv* env, jobject, jlong context) {
    return call(env, jobject{nullptr}, [&]() -> jobject {
        const auto perf = find(context)->GetPerformanceData();
        const auto type = env->FindClass("com/haishinkit/srt/SrtPerformanceData");
        if (!type) return nullptr;
        const auto constructor = env->GetMethodID(type, "<init>", "(JJIIDDD)V");
        if (!constructor) return nullptr;
        return env->NewObject(type, constructor,
            static_cast<jlong>(perf.byteSentTotal), static_cast<jlong>(perf.byteRecvTotal),
            static_cast<jint>(perf.byteSndBuf), static_cast<jint>(perf.byteRcvBuf),
            perf.msRTT, perf.mbpsSendRate, perf.mbpsRecvRate);
    });
}

JNIEXPORT jlong JNICALL Java_com_haishinkit_srt_SrtSocket_nativeAwait(
    JNIEnv* env, jobject, jlong context, jint events, jobject callback) {
    return call(env, jlong{0}, [&] {
        auto waiter = std::make_shared<JavaWaiter>(env, callback);
        return static_cast<jlong>(find(context)->Wait(events, [waiter] { waiter->Complete(); }));
    });
}

JNIEXPORT void JNICALL Java_com_haishinkit_srt_SrtSocket_nativeCancelAwait(
    JNIEnv* env, jobject, jlong context, jlong token) {
    call(env, [&] {
        const auto socket = find(context, false);
        if (socket) socket->CancelWait(token);
        else haishinkit::SrtEventLoop::Shared().Cancel(token);
    });
}

JNIEXPORT void JNICALL Java_com_haishinkit_srt_SrtSocket_nativeClose(JNIEnv* env, jobject, jlong context) {
    call(env, [&] { release(context); });
}
} // extern "C"
