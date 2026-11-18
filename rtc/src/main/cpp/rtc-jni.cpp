#include <jni.h>
#include <rtc/rtc.hpp>
#include "BoundedH264Depacketizer.h"
#include "RtpPaddingStripper.h"
#include "FirHandler.h"
#include <atomic>
#include <map>
#include <mutex>
#include <memory>
#include <random>
#include <codecvt>
#include <locale>

namespace {
JavaVM *vm = nullptr;
struct Env {
    JNIEnv *env = nullptr;
    bool attached = false;
    Env() {
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
#if defined(__ANDROID__) || defined(HAISHINKIT_NDK_JNI)
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
#else
            attached = vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) == JNI_OK;
#endif
        }
    }
    ~Env() { if (attached) vm->DetachCurrentThread(); }
};
struct Peer {
    jobject owner;
    jmethodID eventMethod, frameMethod;
    std::shared_ptr<rtc::PeerConnection> pc;
    std::shared_ptr<rtc::Track> audio, video;
    std::shared_ptr<rtc::DataChannel> channel;
    std::mutex channelMutex;
    Peer(JNIEnv *env, jobject object) : owner(env->NewGlobalRef(object)) {
        auto cls = env->GetObjectClass(object);
        eventMethod = env->GetMethodID(cls, "onEvent", "(IILjava/lang/String;)V");
        frameMethod = env->GetMethodID(cls, "onFrame", "(Z[BJ)V");
        env->DeleteLocalRef(cls);
    }
    ~Peer() { Env e; if (e.env) e.env->DeleteGlobalRef(owner); }
    void event(int kind, int value = 0, const std::string &text = "") {
        Env e;
        if (!e.env) return;
        std::u16string utf16;
        try { utf16 = std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.from_bytes(text); }
        catch (const std::exception &) { return; }
        auto string = e.env->NewString(reinterpret_cast<const jchar *>(utf16.data()), static_cast<jsize>(utf16.size()));
        if (!string) { e.env->ExceptionClear(); return; }
        e.env->CallVoidMethod(owner, eventMethod, kind, value, string);
        e.env->DeleteLocalRef(string);
        // Kotlin callbacks are nonthrowing, but never leave a pending Java exception
        // on a libdatachannel worker if allocation/callback code unexpectedly fails.
        if (e.env->ExceptionCheck()) e.env->ExceptionClear();
    }
    void frame(bool isAudio, const rtc::binary &data, uint32_t timestamp) {
        if (data.size() > 4 * 1024 * 1024) { event(2, 0, "Received frame exceeds 4 MiB"); return; }
        Env e;
        if (!e.env) return;
        auto bytes = e.env->NewByteArray(static_cast<jsize>(data.size()));
        if (!bytes) { e.env->ExceptionClear(); return; }
        e.env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte *>(data.data()));
        e.env->CallVoidMethod(owner, frameMethod, static_cast<jboolean>(isAudio), bytes, static_cast<jlong>(timestamp));
        e.env->DeleteLocalRef(bytes);
        if (e.env->ExceptionCheck()) e.env->ExceptionClear();
    }
};
std::mutex registryMutex;
std::map<jlong, std::shared_ptr<Peer>> peers;
std::atomic<jlong> nextId{1};
std::shared_ptr<Peer> get(jlong id) {
    std::lock_guard<std::mutex> guard(registryMutex);
    auto it = peers.find(id);
    if (it == peers.end()) throw std::runtime_error("RTC peer is closed");
    return it->second;
}
std::string string(JNIEnv *env, jstring value) {
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) throw std::runtime_error("Unable to read JNI string");
    std::u16string input(reinterpret_cast<const char16_t *>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars);
    return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.to_bytes(input);
}
void error(JNIEnv *env, const std::exception &ex) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), ex.what());
}
void configureChannel(const std::shared_ptr<Peer> &p, std::shared_ptr<rtc::DataChannel> channel) {
    std::lock_guard<std::mutex> guard(p->channelMutex);
    // The session exposes one text channel. Do not replace it with a remotely opened channel.
    if (p->channel) { channel->close(); return; }
    p->channel = std::move(channel);
    std::weak_ptr<Peer> weak = p;
    p->channel->onOpen([weak] { if (auto p = weak.lock()) p->event(6); });
    p->channel->onClosed([weak] { if (auto p = weak.lock()) p->event(2, 0, "Data channel closed"); });
    p->channel->onError([weak](std::string message) { if (auto p = weak.lock()) p->event(2, 0, message); });
    p->channel->onMessage([weak](rtc::message_variant message) {
        if (auto text = std::get_if<std::string>(&message)) {
            if (text->size() > 1024 * 1024) {
                if (auto p = weak.lock()) p->event(2, 0, "Data message exceeds 1 MiB");
            } else if (auto p = weak.lock()) p->event(7, 0, *text);
        }
    });
}
void track(const std::shared_ptr<Peer> &p, bool audio, bool publishing, bool duplex = false) {
    auto direction = duplex ? rtc::Description::Direction::SendRecv : publishing ? rtc::Description::Direction::SendOnly : rtc::Description::Direction::RecvOnly;
    rtc::Description::Media media = [&]() -> rtc::Description::Media {
        if (audio) {
            rtc::Description::Audio description("audio", direction);
            description.addOpusCodec(111);
            return description;
        }
        rtc::Description::Video description("video", direction);
        description.addH264Codec(96, "profile-level-id=42e01f;packetization-mode=1;level-asymmetry-allowed=1");
        return description;
    }();
    const uint32_t ssrc = std::random_device{}();
    if (publishing) media.addSSRC(ssrc, "haishinkit", "haishinkit", audio ? "audio" : "video");
    auto t = p->pc->addTrack(media);
    if (audio) p->audio = t; else p->video = t;
    std::weak_ptr<Peer> weak = p;
    t->onOpen([weak, audio] { if (auto p = weak.lock()) p->event(5, audio ? 1 : 0); });
    t->onError([weak](std::string message) { if (auto p = weak.lock()) p->event(2, 0, message); });
    std::shared_ptr<rtc::MediaHandler> sender;
    if (publishing) {
        auto config = std::make_shared<rtc::RtpPacketizationConfig>(ssrc, "haishinkit", audio ? 111 : 96, audio ? 48000 : 90000);
        std::shared_ptr<rtc::MediaHandler> handler;
        if (audio) handler = std::make_shared<rtc::OpusRtpPacketizer>(config);
        else handler = std::make_shared<rtc::H264RtpPacketizer>(rtc::NalUnit::Separator::StartSequence, config);
        handler->addToChain(std::make_shared<rtc::RtcpSrReporter>(config));
        handler->addToChain(std::make_shared<rtc::RtcpNackResponder>());
        if (!audio) {
            handler->addToChain(std::make_shared<FirHandler>([weak] { if (auto p = weak.lock()) p->event(3); }));
            handler->addToChain(std::make_shared<rtc::PliHandler>([weak] { if (auto p = weak.lock()) p->event(3); }));
            handler->addToChain(std::make_shared<rtc::RembHandler>([weak](unsigned int bitrate) { if (auto p = weak.lock()) p->event(4, static_cast<int>(std::min(bitrate, 2147483647u))); }));
        }
        sender = handler;
        t->setMediaHandler(handler);
    }
    if (!publishing || duplex) {
        std::shared_ptr<rtc::MediaHandler> handler;
        if (audio) handler = std::make_shared<rtc::OpusRtpDepacketizer>();
        else handler = std::make_shared<BoundedH264Depacketizer>();
        if (audio) handler->addToChain(std::make_shared<RtpPaddingStripper>());
        // Incoming handlers run tail-first: count valid RTP (including padding
        // probes) in receiver reports before stripping the codec payload.
        handler->addToChain(std::make_shared<rtc::RtcpReceivingSession>());
        if (sender) sender->addToChain(handler);
        else t->setMediaHandler(handler);
        t->onFrame([weak, audio](rtc::binary data, rtc::FrameInfo info) {
            if (auto p = weak.lock()) p->frame(audio, data, info.timestamp);
        });
    }
}
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *value, void *) { vm = value; return JNI_VERSION_1_6; }
extern "C" JNIEXPORT jlong JNICALL Java_com_haishinkit_rtc_NativePeer_create(JNIEnv *env, jobject object, jboolean publishing, jboolean audio, jboolean video, jobjectArray servers, jboolean duplex, jstring channelLabel) {
    try {
        auto p = std::make_shared<Peer>(env, object);
        rtc::Configuration config;
        config.disableAutoNegotiation = true;
        config.forceMediaTransport = true;
        for (jsize i = 0; i < env->GetArrayLength(servers); ++i) {
            auto server = static_cast<jstring>(env->GetObjectArrayElement(servers, i));
            config.iceServers.emplace_back(string(env, server));
            env->DeleteLocalRef(server);
        }
        p->pc = std::make_shared<rtc::PeerConnection>(config);
        std::weak_ptr<Peer> weak = p;
        p->pc->onStateChange([weak](rtc::PeerConnection::State state) { if (auto p = weak.lock()) p->event(0, static_cast<int>(state)); });
        p->pc->onGatheringStateChange([weak](rtc::PeerConnection::GatheringState state) {
            if (state == rtc::PeerConnection::GatheringState::Complete) {
                if (auto p = weak.lock()) {
                    auto description = p->pc->localDescription();
                    if (description) p->event(1, 0, std::string(*description));
                }
            }
        });
        if (duplex) p->pc->onDataChannel([weak](std::shared_ptr<rtc::DataChannel> channel) {
            if (auto p = weak.lock()) configureChannel(p, std::move(channel));
        });
        if (audio) track(p, true, publishing, duplex);
        if (video) track(p, false, publishing);
        const auto label = string(env, channelLabel);
        if (!label.empty()) configureChannel(p, p->pc->createDataChannel(label));
        auto id = nextId++;
        std::lock_guard<std::mutex> guard(registryMutex);
        peers.emplace(id, p);
        return id;
    } catch (const std::exception &ex) { error(env, ex); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_haishinkit_rtc_NativePeer_localDescription(JNIEnv *env, jobject, jlong id, jstring type) {
    try { get(id)->pc->setLocalDescription(rtc::Description::stringToType(string(env, type))); }
    catch (const std::exception &ex) { error(env, ex); }
}
extern "C" JNIEXPORT void JNICALL Java_com_haishinkit_rtc_NativePeer_remoteDescription(JNIEnv *env, jobject, jlong id, jstring sdp, jstring type) {
    try { get(id)->pc->setRemoteDescription(rtc::Description(string(env, sdp), string(env, type))); }
    catch (const std::exception &ex) { error(env, ex); }
}
extern "C" JNIEXPORT void JNICALL Java_com_haishinkit_rtc_NativePeer_sendFrame(JNIEnv *env, jobject, jlong id, jboolean audio, jbyteArray bytes, jlong timeUs) {
    try {
        auto p = get(id);
        auto t = audio ? p->audio : p->video;
        if (!t || !t->isOpen()) throw std::runtime_error("RTC track is not open");
        auto size = env->GetArrayLength(bytes);
        rtc::binary data(size);
        env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(data.data()));
        t->sendFrame(std::move(data), rtc::FrameInfo(std::chrono::duration<double>(static_cast<double>(timeUs) / 1000000.0)));
    } catch (const std::exception &ex) { error(env, ex); }
}
extern "C" JNIEXPORT void JNICALL Java_com_haishinkit_rtc_NativePeer_keyframe(JNIEnv *env, jobject, jlong id) {
    try { auto p = get(id); if (p->video && p->video->isOpen()) p->video->requestKeyframe(); }
    catch (const std::exception &ex) { error(env, ex); }
}
extern "C" JNIEXPORT void JNICALL Java_com_haishinkit_rtc_NativePeer_destroy(JNIEnv *env, jobject, jlong id) {
    try {
        std::shared_ptr<Peer> p;
        {
            std::lock_guard<std::mutex> guard(registryMutex);
            auto it = peers.find(id);
            if (it == peers.end()) return;
            p = std::move(it->second);
            peers.erase(it);
        }
        p->pc->close();
    } catch (const std::exception &ex) { error(env, ex); }
}
extern "C" JNIEXPORT void JNICALL Java_com_haishinkit_rtc_NativePeer_sendMessage(JNIEnv *env, jobject, jlong id, jstring text) {
    try {
        auto p = get(id);
        std::shared_ptr<rtc::DataChannel> channel;
        {
            std::lock_guard<std::mutex> guard(p->channelMutex);
            channel = p->channel;
        }
        if (!channel || !channel->isOpen()) throw std::runtime_error("Data channel is not open");
        auto message = string(env, text);
        if (message.size() > 1024 * 1024) throw std::runtime_error("Data message exceeds 1 MiB");
        if (channel->bufferedAmount() + message.size() > 4 * 1024 * 1024)
            throw std::runtime_error("Data channel send buffer is full");
        // A false return means buffered, not rejected. Do not ask the caller to resend it.
        channel->send(message);
    } catch (const std::exception &ex) { error(env, ex); }
}
