#include "audio_mixer.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>

using namespace haishinkit::audio;
static void check(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }

static void kernels() {
    for (int count : {1, 3, 7, 8, 9, 31, 960, 967}) {
        std::vector<int16_t> source(count + 2, 12345), scalar(count + 2, 1234), vector(count + 2, 1234);
        std::vector<int32_t> sum(count + 2, 0), vectorSum(count + 2, 0);
        for (int i = 0; i < count; ++i) source[i + 1] = static_cast<int16_t>((i * 2531) % 65536 - 32768);
        for (int track = 0; track < 3; ++track) {
            addPcm16(source.data() + 1, sum.data() + 1, count, false);
            addPcm16(source.data() + 1, vectorSum.data() + 1, count, true);
        }
        finishPcm16(sum.data() + 1, scalar.data() + 1, count, false);
        finishPcm16(vectorSum.data() + 1, vector.data() + 1, count, true);
        check(sum == vectorSum && scalar == vector, "PCM16 SIMD differs from scalar or overwrites guard");
        std::vector<float> floats(count + 2), a(count + 2), b(count + 2);
        for (int i = 0; i < count; ++i) floats[i + 1] = source[i + 1] / 32768.f;
        for (float gain : {.1f, 1.f, -0.7f}) {
            addFloat32(floats.data() + 1, a.data() + 1, gain, count, false);
            addFloat32(floats.data() + 1, b.data() + 1, gain, count, true);
        }
        check(a == b, "Float32 SIMD differs from scalar");
        finishFloat32(a.data() + 1, scalar.data() + 1, count, false);
        finishFloat32(b.data() + 1, vector.data() + 1, count, true);
        check(scalar == vector, "Float32 SIMD clipping differs from scalar");
    }
    const int16_t positive = 30000, negative = -30000;
    int32_t sum = 0;
    int16_t output = 0;
    addPcm16(&positive, &sum, 1, true); addPcm16(&positive, &sum, 1, true); addPcm16(&negative, &sum, 1, true);
    finishPcm16(&sum, &output, 1, true);
    check(output == 30000, "Mix clips before final output");
}

static void engine() {
    Mixer mix(48000, 2, 480);
    mix.add(0); mix.add(1); mix.add(2);
    std::vector<int16_t> mono(480, 10000), stereo(960, -1000), out(960);
    mix.append(0, mono.data(), 960, {48000, 1, 2});
    mix.append(1, stereo.data(), 1920, {48000, 2, 2});
    mix.settings(0, .5, false);
    mix.render(out.data(), false);
    for (auto value : out) check(value == 4000, "Track mixing or mono to stereo mapping failed");
    mix.append(0, mono.data(), 960, {48000, 1, 2});
    mix.render(out.data(), true);
    for (auto value : out) check(value == 0, "Master mute failed");
    mix.render(out.data(), false);
    for (auto value : out) check(value == 0, "Muted input replays after unmuting");
    std::vector<float> invalid(960, std::numeric_limits<float>::quiet_NaN());
    mix.append(2, invalid.data(), 3840, {48000, 2, 4});
    mix.render(out.data(), false);
    for (auto value : out) check(value == 0, "Non-finite float input is not sanitized");
    std::vector<int16_t> overflow(48000, 20000);
    mix.append(0, overflow.data(), 96000, {48000, 1, 2});
    check(mix.droppedFrames() > 30000, "Input queue is unbounded");
    mix.remove(0);
    mix.render(out.data(), false);
    for (auto value : out) check(value == 0, "Detached input remains audible");
}

static void resampling() {
    constexpr double pi = 3.14159265358979323846;
    for (int rate : {16000, 44100, 96000}) {
        Mixer mix(48000, 1, 480);
        mix.add(0);
        std::vector<int16_t> input(rate / 100), output(480);
        double signal = 0, error = 0;
        for (int block = 0; block < 100; ++block) {
            for (int i = 0; i < static_cast<int>(input.size()); ++i) input[i] = 10000;
            mix.append(0, input.data(), input.size() * 2, {rate, 1, 2});
            mix.render(output.data(), false);
            if (block > 2) for (int sample : output) { signal += 1; error += std::abs(sample - 10000); }
        }
        check(error / signal < 3, "Resampling does not preserve DC gain or loses frames");
    }
    Mixer mix(48000, 1, 480); mix.add(0);
    std::vector<int16_t> input(960), output(480);
    double energy = 0;
    for (int b = 0; b < 100; ++b) {
        for (int i = 0; i < 960; ++i) input[i] = static_cast<int16_t>(25000 * std::sin(2 * pi * 30000 * (b * 960 + i) / 96000));
        mix.append(0, input.data(), 1920, {96000, 1, 2}); mix.render(output.data(), false);
        if (b > 2) for (int sample : output) energy += static_cast<double>(sample) * sample;
    }
    check(std::sqrt(energy / (97 * 480)) < 1000, "Downsampling aliases high-frequency input");
}

static void benchmark() {
    constexpr int count = 960, iterations = 10000;
    std::vector<int16_t> pcm(count, 1000), out(count);
    std::vector<int32_t> wide(count);
    std::vector<float> fp(count, .1f), mixed(count);
    volatile int checksum = 0;
    for (bool simd : {false, true}) for (bool floating : {false, true}) {
        auto start = std::chrono::steady_clock::now();
        for (int n = 0; n < iterations; ++n) {
            if (floating) {
                std::fill(mixed.begin(), mixed.end(), 0);
                for (int t = 0; t < 3; ++t) addFloat32(fp.data(), mixed.data(), 1, count, simd);
                finishFloat32(mixed.data(), out.data(), count, simd);
            } else {
                std::fill(wide.begin(), wide.end(), 0);
                for (int t = 0; t < 3; ++t) addPcm16(pcm.data(), wide.data(), count, simd);
                finishPcm16(wide.data(), out.data(), count, simd);
            }
            checksum += out[n % count];
        }
        auto ns = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).count();
        std::cout << (floating ? "Float32" : "PCM16/int32") << (simd ? "/NEON" : "/C++") << ": " << ns / iterations << " ns / 3-track 480-frame stereo mix\n";
    }
    (void)checksum;
    Mixer engine(48000, 2, 480);
    engine.add(0); engine.add(1); engine.add(2);
    std::vector<int16_t> mic(160, 1000), asset(882, 1000), projection(960, 1000);
    std::vector<int64_t> durations;
    durations.reserve(3000);
    for (int n = 0; n < 3500; ++n) {
        auto start = std::chrono::steady_clock::now();
        engine.append(0, mic.data(), mic.size() * 2, {16000, 1, 2});
        engine.append(1, asset.data(), asset.size() * 2, {44100, 2, 2});
        engine.append(2, projection.data(), projection.size() * 2, {48000, 2, 2});
        engine.render(out.data(), false);
        auto ns = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).count();
        if (n >= 500) durations.push_back(ns);
        checksum += out[n % count];
    }
    std::sort(durations.begin(), durations.end());
    std::cout << "3-source append+resample+mix+PCM16: p50=" << durations[1500] << " ns, p95=" << durations[2850]
              << " ns, p99=" << durations[2970] << " ns (10ms audio budget)\n";
}

int main() {
    try { kernels(); engine(); resampling(); benchmark(); std::cout << "All native audio tests passed (NEON=" << hasNeon() << ")\n"; }
    catch (const std::exception& cause) { std::cerr << cause.what() << '\n'; return 1; }
}
