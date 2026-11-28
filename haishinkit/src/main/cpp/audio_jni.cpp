#include "audio_mixer.h"
#include <jni.h>
#include <stdexcept>

using haishinkit::audio::Mixer;
namespace {
Mixer& engine(jlong handle) {
    if (!handle) throw std::invalid_argument("Audio mixer is closed");
    return *reinterpret_cast<Mixer*>(handle);
}
void error(JNIEnv* env, const std::exception& cause) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), cause.what());
}
}
extern "C" {
JNIEXPORT jlong JNICALL Java_com_haishinkit_media_audio_NativeAudioMixer_nativeCreate(JNIEnv* env, jobject, jint rate, jint channels, jint frames) {
    try { return reinterpret_cast<jlong>(new Mixer(rate, channels, frames)); }
    catch (const std::exception& cause) { error(env, cause); return 0; }
}
JNIEXPORT void JNICALL Java_com_haishinkit_media_audio_NativeAudioMixer_nativeClose(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Mixer*>(handle);
}
JNIEXPORT void JNICALL Java_com_haishinkit_media_audio_NativeAudioMixer_nativeAdd(JNIEnv* env, jobject, jlong handle, jint id) {
    try { engine(handle).add(id); } catch (const std::exception& cause) { error(env, cause); }
}
JNIEXPORT void JNICALL Java_com_haishinkit_media_audio_NativeAudioMixer_nativeRemove(JNIEnv* env, jobject, jlong handle, jint id) {
    try { engine(handle).remove(id); } catch (const std::exception& cause) { error(env, cause); }
}
JNIEXPORT void JNICALL Java_com_haishinkit_media_audio_NativeAudioMixer_nativeClear(JNIEnv* env, jobject, jlong handle, jint id) {
    try { engine(handle).clear(id); } catch (const std::exception& cause) { error(env, cause); }
}
JNIEXPORT void JNICALL Java_com_haishinkit_media_audio_NativeAudioMixer_nativeSettings(JNIEnv* env, jobject, jlong handle, jint id, jfloat gain, jboolean muted) {
    try { engine(handle).settings(id, gain, muted); } catch (const std::exception& cause) { error(env, cause); }
}
JNIEXPORT void JNICALL Java_com_haishinkit_media_audio_NativeAudioMixer_nativeAppend(JNIEnv* env, jobject, jlong handle, jint id, jobject input, jint offset, jint bytes, jint rate, jint channels, jint encoding) {
    try {
        auto* address = static_cast<uint8_t*>(env->GetDirectBufferAddress(input));
        const auto capacity = env->GetDirectBufferCapacity(input);
        if (!address || offset < 0 || bytes < 0 || static_cast<jlong>(offset) + bytes > capacity)
            throw std::invalid_argument("Audio input requires a valid direct buffer range");
        engine(handle).append(id, address + offset, bytes, {rate, channels, encoding});
    } catch (const std::exception& cause) { error(env, cause); }
}
JNIEXPORT void JNICALL Java_com_haishinkit_media_audio_NativeAudioMixer_nativeRender(JNIEnv* env, jobject, jlong handle, jobject output, jint bytes, jboolean muted) {
    try {
        auto* address = static_cast<int16_t*>(env->GetDirectBufferAddress(output));
        if (!address || bytes != engine(handle).outputBytes() || env->GetDirectBufferCapacity(output) < bytes) throw std::invalid_argument("Invalid audio output buffer");
        engine(handle).render(address, muted);
    } catch (const std::exception& cause) { error(env, cause); }
}
JNIEXPORT jlong JNICALL Java_com_haishinkit_media_audio_NativeAudioMixer_nativeDroppedFrames(JNIEnv* env, jobject, jlong handle) {
    try { return static_cast<jlong>(engine(handle).droppedFrames()); }
    catch (const std::exception& cause) { error(env, cause); return 0; }
}
}
