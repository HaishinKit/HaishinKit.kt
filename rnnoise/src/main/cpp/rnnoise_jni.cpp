#include <jni.h>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <new>
#include "rnnoise.h"

namespace {
constexpr int frameSize = 480;
struct Channel {
    DenoiseState* state = nullptr;
    std::array<float, frameSize> input{};
    std::array<float, frameSize> wet{};
    std::array<float, 3 * frameSize> dry{};
    ~Channel() { if (state) rnnoise_destroy(state); }
};
struct Processor {
    std::array<Channel, 2> channel;
    int channels;
    int framePosition = 0;
    int dryPosition = 0;
    explicit Processor(int count) : channels(count) { for (int c = 0; c < channels; ++c) channel[c].state = rnnoise_create(nullptr); }
};
void error(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_haishinkit_rnnoise_RnnoiseAudioProcessor_nativeCreate(JNIEnv* env, jobject, jint channels) {
    if (channels < 1 || channels > 2 || rnnoise_get_frame_size() != frameSize) {
        error(env, "Unsupported RNNoise channel count/frame size"); return 0;
    }
    auto* p = new (std::nothrow) Processor(channels);
    if (!p || !p->channel[0].state || (channels == 2 && !p->channel[1].state)) { delete p; return 0; }
    return reinterpret_cast<jlong>(p);
}
extern "C" JNIEXPORT void JNICALL
Java_com_haishinkit_rnnoise_RnnoiseAudioProcessor_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Processor*>(handle);
}
extern "C" JNIEXPORT void JNICALL
Java_com_haishinkit_rnnoise_RnnoiseAudioProcessor_nativeProcess(
    JNIEnv* env, jobject, jlong handle, jobject input, jint offset,
    jobject output, jint frames, jboolean floating, jboolean bypass) {
    auto* p = reinterpret_cast<Processor*>(handle);
    if (!p || frames < 0 || offset < 0) { error(env, "Invalid RNNoise buffer"); return; }
    const int width = floating ? 4 : 2;
    const int64_t bytes = static_cast<int64_t>(frames) * p->channels * width;
    auto* in = static_cast<uint8_t*>(env->GetDirectBufferAddress(input));
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(output));
    if (!in || !out || bytes + offset > env->GetDirectBufferCapacity(input) || bytes > env->GetDirectBufferCapacity(output)) {
        error(env, "RNNoise requires valid direct buffers"); return;
    }
    in += offset;
    for (int f = 0; f < frames; ++f) {
        for (int c = 0; c < p->channels; ++c) {
            const int at = (f * p->channels + c) * width;
            float value;
            if (floating) { std::memcpy(&value, in + at, 4); value *= 32768.f; }
            else { int16_t sample; std::memcpy(&sample, in + at, 2); value = sample; }
            if (!std::isfinite(value)) value = 0;
            value = std::clamp(value, -32768.f, floating ? 32768.f : 32767.f);
            auto& ch = p->channel[c];
            const float result = bypass ? ch.dry[p->dryPosition] : ch.wet[p->framePosition];
            ch.dry[p->dryPosition] = value;
            ch.input[p->framePosition] = value;
            const float clipped = std::clamp(std::isfinite(result) ? result : 0.f, -32768.f, floating ? 32768.f : 32767.f);
            if (floating) { const float sample = clipped / 32768.f; std::memcpy(out + at, &sample, 4); }
            else { const auto sample = static_cast<int16_t>(std::lrintf(clipped)); std::memcpy(out + at, &sample, 2); }
        }
        p->dryPosition = (p->dryPosition + 1) % (3 * frameSize);
        if (++p->framePosition == frameSize) {
            for (int c = 0; c < p->channels; ++c)
                rnnoise_process_frame(p->channel[c].state, p->channel[c].wet.data(), p->channel[c].input.data());
            p->framePosition = 0;
        }
    }
}
