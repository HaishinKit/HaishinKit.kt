#include "audio_mixer.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace haishinkit::audio {
bool hasNeon() {
#if defined(__aarch64__)
    return true;
#else
    return false;
#endif
}

void addPcm16(const int16_t* input, int32_t* output, int count, bool simd) {
    int i = 0;
#if defined(__aarch64__)
    if (simd) for (; i + 8 <= count; i += 8) {
        const auto x = vld1q_s16(input + i);
        vst1q_s32(output + i, vaddw_s16(vld1q_s32(output + i), vget_low_s16(x)));
        vst1q_s32(output + i + 4, vaddw_s16(vld1q_s32(output + i + 4), vget_high_s16(x)));
    }
#else
    (void)simd;
#endif
    for (; i < count; ++i) output[i] += input[i];
}

void finishPcm16(const int32_t* input, int16_t* output, int count, bool simd) {
    int i = 0;
#if defined(__aarch64__)
    if (simd) for (; i + 8 <= count; i += 8) {
        vst1q_s16(output + i, vcombine_s16(vqmovn_s32(vld1q_s32(input + i)), vqmovn_s32(vld1q_s32(input + i + 4))));
    }
#else
    (void)simd;
#endif
    for (; i < count; ++i) output[i] = static_cast<int16_t>(std::clamp(input[i], -32768, 32767));
}

void addFloat32(const float* input, float* output, float gain, int count, bool simd) {
    int i = 0;
#if defined(__aarch64__)
    if (simd) for (; i + 4 <= count; i += 4) {
        // Separate multiply/add keeps scalar and vector paths deterministic (no FMA rounding).
        vst1q_f32(output + i, vaddq_f32(vld1q_f32(output + i), vmulq_n_f32(vld1q_f32(input + i), gain)));
    }
#else
    (void)simd;
#endif
    for (; i < count; ++i) output[i] += input[i] * gain;
}

void finishFloat32(const float* input, int16_t* output, int count, bool simd) {
    int i = 0;
#if defined(__aarch64__)
    if (simd) for (; i + 8 <= count; i += 8) {
        const auto scale = vdupq_n_f32(32768.f);
        auto a = vmulq_f32(vld1q_f32(input + i), scale);
        auto b = vmulq_f32(vld1q_f32(input + i + 4), scale);
        a = vmaxq_f32(vdupq_n_f32(-32768.f), vminq_f32(vdupq_n_f32(32767.f), a));
        b = vmaxq_f32(vdupq_n_f32(-32768.f), vminq_f32(vdupq_n_f32(32767.f), b));
        vst1q_s16(output + i, vcombine_s16(vqmovn_s32(vcvtq_s32_f32(a)), vqmovn_s32(vcvtq_s32_f32(b))));
    }
#else
    (void)simd;
#endif
    for (; i < count; ++i) output[i] = static_cast<int16_t>(std::clamp(input[i] * 32768.f, -32768.f, 32767.f));
}

Mixer::Mixer(int rate, int channels, int frames)
    : rate_(rate), channels_(channels), frames_(frames) {
    if (rate < 8000 || rate > 96000 || (channels != 1 && channels != 2) || frames < 1 || frames > rate)
        throw std::invalid_argument("Invalid mixer output format");
    accumulator_.resize(frames * channels);
    scratch_.resize(frames * channels);
}

Mixer::Track* Mixer::find(int id) {
    for (auto& track : tracks_) if (track.attached && track.id == id) return &track;
    return nullptr;
}

void Mixer::add(int id) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (find(id)) return;
    for (auto& track : tracks_) if (!track.attached) {
        track = Track{};
        track.id = id;
        track.attached = true;
        return;
    }
    throw std::invalid_argument("At most 32 audio tracks can be mixed");
}

void Mixer::remove(int id) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (auto* track = find(id)) *track = Track{};
}

void Mixer::settings(int id, float gain, bool muted) {
    if (!std::isfinite(gain) || gain < 0 || gain > 4) throw std::invalid_argument("Audio volume must be between 0 and 4");
    std::lock_guard<std::mutex> lock(mutex_);
    if (auto* track = find(id)) { track->gain = gain; track->muted = muted; }
}

void Mixer::clear(int id) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (auto* track = find(id)) {
        track->head = 0;
        track->size = track->format.rate && track->format.rate != rate_ ? taps / 2 : 0;
        track->phase = track->size;
        std::fill(track->ring.begin(), track->ring.end(), 0);
    }
}

void Mixer::Track::configure(Format input, int outputRate) {
    format = input;
    capacity = input.rate / 4 + taps * 2; // Bounded 250 ms per source, including filter history.
    ring.assign(capacity * input.channels, 0);
    head = 0;
    size = input.rate == outputRate ? 0 : taps / 2;
    phase = size;
    if (input.rate == outputRate) return;
    const double cutoff = 0.94 * std::min(1., static_cast<double>(outputRate) / input.rate);
    constexpr double pi = 3.14159265358979323846;
    for (int p = 0; p < phases; ++p) {
        double sum = 0;
        for (int k = 0; k < taps; ++k) {
            const double x = k - (taps / 2 - 1) - static_cast<double>(p) / phases;
            const double sinc = std::abs(x) < 1e-10 ? cutoff : std::sin(pi * x * cutoff) / (pi * x);
            const double window = 0.5 - 0.5 * std::cos(2 * pi * (k + .5) / taps);
            coefficients[p * taps + k] = static_cast<float>(sinc * window);
            sum += sinc * window;
        }
        for (int k = 0; k < taps; ++k) coefficients[p * taps + k] /= static_cast<float>(sum);
    }
}

float Mixer::Track::sample(int frame, int channel) const {
    if (frame < 0 || frame >= size) return 0;
    return ring[((head + frame) % capacity) * format.channels + channel];
}

void Mixer::Track::consume(int frames) {
    frames = std::min(size, frames);
    head = (head + frames) % capacity;
    size -= frames;
    phase = std::max(0., phase - frames);
}

void Mixer::append(int id, const void* pcm, int bytes, Format format) {
    if (format.rate < 8000 || format.rate > 192000 || format.channels < 1 || format.channels > 2 ||
        (format.encoding != 2 && format.encoding != 4)) throw std::invalid_argument("Audio input requires mono/stereo PCM16 or float32");
    const int sampleBytes = format.encoding == 2 ? 2 : 4;
    if (bytes < 0 || bytes % (sampleBytes * format.channels)) throw std::invalid_argument("Incomplete PCM frame");
    std::lock_guard<std::mutex> lock(mutex_);
    auto* track = find(id);
    if (!track) return;
    if (track->format.rate != format.rate || track->format.channels != format.channels || track->format.encoding != format.encoding)
        track->configure(format, rate_);
    const int frames = bytes / (sampleBytes * format.channels);
    for (int f = 0; f < frames; ++f) {
        if (track->size == track->capacity) { track->consume(1); ++track->dropped; }
        const int position = ((track->head + track->size) % track->capacity) * format.channels;
        for (int c = 0; c < format.channels; ++c) {
            const int index = f * format.channels + c;
            float value;
            if (format.encoding == 2) {
                int16_t sample;
                std::memcpy(&sample, static_cast<const uint8_t*>(pcm) + index * 2, 2);
                value = sample / 32768.f;
            } else {
                std::memcpy(&value, static_cast<const uint8_t*>(pcm) + index * 4, 4);
            }
            track->ring[position + c] = std::isfinite(value) ? std::clamp(value, -1.f, 1.f) : 0;
        }
        ++track->size;
    }
}

void Mixer::render(int16_t* output, bool muted) {
    std::lock_guard<std::mutex> lock(mutex_);
    std::fill(accumulator_.begin(), accumulator_.end(), 0);
    for (auto& track : tracks_) {
        if (!track.attached || track.size == 0) continue;
        std::fill(scratch_.begin(), scratch_.end(), 0);
        for (int f = 0; f < frames_; ++f) {
            float values[2]{};
            if (track.format.rate == rate_) {
                if (track.size == 0) break;
                for (int c = 0; c < track.format.channels; ++c) values[c] = track.sample(0, c);
                track.consume(1);
            } else {
                const int base = static_cast<int>(track.phase);
                if (base + taps / 2 >= track.size) break;
                const int p = std::min(phases - 1, static_cast<int>((track.phase - base) * phases));
                for (int c = 0; c < track.format.channels; ++c)
                    for (int k = 0; k < taps; ++k) values[c] += track.sample(base + k - (taps / 2 - 1), c) * track.coefficients[p * taps + k];
                track.phase += static_cast<double>(track.format.rate) / rate_;
                track.consume(std::max(0, static_cast<int>(track.phase) - taps / 2));
            }
            if (channels_ == 1) scratch_[f] = track.format.channels == 1 ? values[0] : (values[0] + values[1]) * .5f;
            else {
                scratch_[f * 2] = values[0];
                scratch_[f * 2 + 1] = track.format.channels == 1 ? values[0] : values[1];
            }
        }
        if (!muted && !track.muted) addFloat32(scratch_.data(), accumulator_.data(), track.gain, frames_ * channels_, true);
    }
    finishFloat32(accumulator_.data(), output, frames_ * channels_, true);
}

uint64_t Mixer::droppedFrames() {
    std::lock_guard<std::mutex> lock(mutex_);
    uint64_t result = 0;
    for (const auto& track : tracks_) result += track.dropped;
    return result;
}
}
