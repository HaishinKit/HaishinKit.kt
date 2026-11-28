#pragma once

#include <array>
#include <cstdint>
#include <mutex>
#include <vector>

namespace haishinkit::audio {
// Scalar paths remain available for bit-exact SIMD regression tests and measurements.
void addPcm16(const int16_t* input, int32_t* output, int count, bool simd);
void finishPcm16(const int32_t* input, int16_t* output, int count, bool simd);
void addFloat32(const float* input, float* output, float gain, int count, bool simd);
void finishFloat32(const float* input, int16_t* output, int count, bool simd);
bool hasNeon();

struct Format {
    int rate;
    int channels;
    int encoding; // 2 = PCM16, 4 = float32, matching AudioFormat.
};

class Mixer {
public:
    static constexpr int maxTracks = 32;
    Mixer(int rate, int channels, int frames);
    void add(int id);
    void remove(int id);
    void clear(int id);
    void settings(int id, float gain, bool muted);
    void append(int id, const void* pcm, int bytes, Format format);
    void render(int16_t* output, bool muted);
    uint64_t droppedFrames();
    int outputBytes() const { return frames_ * channels_ * 2; }

private:
    static constexpr int taps = 32;
    static constexpr int phases = 128;
    struct Track {
        int id = 0;
        bool attached = false;
        float gain = 1;
        bool muted = false;
        Format format{0, 0, 0};
        std::vector<float> ring;
        std::array<float, taps * phases> coefficients{};
        int capacity = 0;
        int head = 0;
        int size = 0;
        double phase = 0;
        uint64_t dropped = 0;
        void configure(Format input, int outputRate);
        float sample(int frame, int channel) const;
        void consume(int frames);
    };
    int rate_;
    int channels_;
    int frames_;
    std::array<Track, maxTracks> tracks_;
    std::vector<float> accumulator_;
    std::vector<float> scratch_;
    std::mutex mutex_;
    Track* find(int id);
};
}
