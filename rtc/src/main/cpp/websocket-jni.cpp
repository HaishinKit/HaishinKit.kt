#include <jni.h>
#include <rtc/rtc.hpp>
#include <atomic>
#include <map>
#include <mutex>

namespace {
JavaVM *wsVm = nullptr;
std::once_flag vmOnce;
struct Env {
    JNIEnv *env = nullptr;
    bool attached = false;
    Env() {
        if (wsVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
#if defined(__ANDROID__) || defined(HAISHINKIT_NDK_JNI)
            attached = wsVm->AttachCurrentThread(&env, nullptr) == JNI_OK;
#else
            attached = wsVm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) == JNI_OK;
#endif
        }
    }
    ~Env() { if (attached) wsVm->DetachCurrentThread(); }
};
struct Socket {
    jobject owner;
    jmethodID callback;
    std::shared_ptr<rtc::WebSocket> socket;
    std::mutex callbackMutex;
    bool closed = false;
    Socket(JNIEnv *env, jobject value) : owner(env->NewGlobalRef(value)) {
        auto cls = env->GetObjectClass(value);
        callback = env->GetMethodID(cls, "onEvent", "(I[B)V");
        env->DeleteLocalRef(cls);
    }
    ~Socket() { Env e; if (e.env) e.env->DeleteGlobalRef(owner); }
    void emit(int kind, const char *data = nullptr, size_t size = 0) {
        std::lock_guard<std::mutex> guard(callbackMutex);
        if (closed) return;
        Env e;
        if (!e.env) return;
        auto bytes = e.env->NewByteArray(static_cast<jsize>(size));
        if (!bytes) { e.env->ExceptionClear(); return; }
        if (size) e.env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
        e.env->CallVoidMethod(owner, callback, kind, bytes);
        e.env->DeleteLocalRef(bytes);
        if (e.env->ExceptionCheck()) e.env->ExceptionClear();
    }
    void close() {
        { std::lock_guard<std::mutex> guard(callbackMutex); closed = true; }
        socket->forceClose();
    }
};
std::mutex registryMutex;
std::map<jlong, std::shared_ptr<Socket>> sockets;
std::atomic<jlong> nextId{1};
std::shared_ptr<Socket> get(jlong id) {
    std::lock_guard<std::mutex> guard(registryMutex);
    auto found = sockets.find(id);
    if (found == sockets.end()) throw std::runtime_error("WebSocket is closed");
    return found->second;
}
std::string string(JNIEnv *env, jstring value) {
    const char *data = env->GetStringUTFChars(value, nullptr);
    if (!data) throw std::runtime_error("Unable to read string");
    std::string result(data);
    env->ReleaseStringUTFChars(value, data);
    return result;
}
void fail(JNIEnv *env) {
    // Native exception messages can include an authenticated URL.
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "WebSocket operation failed");
}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_haishinkit_rtc_NativeWebSocket_create(JNIEnv *env, jobject owner, jstring url, jstring roots) {
    try {
        std::call_once(vmOnce, [env] { env->GetJavaVM(&wsVm); });
        auto state = std::make_shared<Socket>(env, owner);
        rtc::WebSocket::Configuration config;
        config.maxMessageSize = 1024 * 1024;
        config.connectionTimeout = std::chrono::seconds(15);
        config.pingInterval = std::chrono::seconds(20);
        const auto ca = string(env, roots);
        if (!ca.empty()) config.caCertificatePemFile = ca;
        state->socket = std::make_shared<rtc::WebSocket>(config);
        std::weak_ptr<Socket> weak = state;
        state->socket->onOpen([weak] { if (auto s = weak.lock()) s->emit(0); });
        state->socket->onClosed([weak] { if (auto s = weak.lock()) s->emit(3); });
        state->socket->onError([weak](std::string) { if (auto s = weak.lock()) s->emit(2); });
        state->socket->onMessage([weak](rtc::message_variant message) {
            if (auto s = weak.lock()) {
                if (auto text = std::get_if<std::string>(&message)) s->emit(1, text->data(), text->size());
                else { auto &bytes = std::get<rtc::binary>(message); s->emit(4, reinterpret_cast<const char *>(bytes.data()), bytes.size()); }
            }
        });
        const auto id = nextId++;
        { std::lock_guard<std::mutex> guard(registryMutex); sockets.emplace(id, state); }
        try { state->socket->open(string(env, url)); }
        catch (...) { std::lock_guard<std::mutex> guard(registryMutex); sockets.erase(id); throw; }
        return id;
    } catch (...) { fail(env); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_haishinkit_rtc_NativeWebSocket_sendMessage(JNIEnv *env, jobject, jlong id, jbyteArray bytes, jboolean binary) {
    try {
        auto state = get(id);
        const auto size = env->GetArrayLength(bytes);
        if (size > 1024 * 1024 || !state->socket->isOpen() || state->socket->bufferedAmount() + size > 1024 * 1024)
            throw std::runtime_error("WebSocket send queue is full");
        std::string data(size, '\0');
        env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(data.data()));
        if (binary) state->socket->send(reinterpret_cast<const std::byte *>(data.data()), size);
        else state->socket->send(std::move(data));
    } catch (...) { fail(env); }
}
extern "C" JNIEXPORT void JNICALL Java_com_haishinkit_rtc_NativeWebSocket_destroy(JNIEnv *env, jobject, jlong id) {
    try {
        std::shared_ptr<Socket> state;
        { std::lock_guard<std::mutex> guard(registryMutex); auto found = sockets.find(id);
          if (found == sockets.end()) return; state = found->second; sockets.erase(found); }
        state->close();
    } catch (...) { fail(env); }
}
