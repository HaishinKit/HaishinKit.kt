#pragma once
#include <rtc/rtc.hpp>
#include <functional>

// RFC 5104 payload-specific FIR (PT 206, FMT 4), missing from the pinned PliHandler.
class FirHandler final : public rtc::MediaHandler {
public:
    explicit FirHandler(std::function<void()> callback) : callback(std::move(callback)) {}
    void incoming(rtc::message_vector &messages, const rtc::message_callback &) override {
        for (const auto &message : messages) {
            if (message->type != rtc::Message::Control) continue;
            size_t offset = 0;
            while (message->size() - offset >= 4) {
                const auto *p = message->data() + offset;
                const auto first = std::to_integer<unsigned>(p[0]);
                const size_t length = (1 + (std::to_integer<unsigned>(p[2]) << 8) + std::to_integer<unsigned>(p[3])) * 4;
                if ((first >> 6) != 2 || length > message->size() - offset) break;
                if (std::to_integer<unsigned>(p[1]) == 206 && (first & 31) == 4 && length >= 20 && (length - 12) % 8 == 0) {
                    callback();
                    break;
                }
                offset += length;
            }
        }
    }
private:
    std::function<void()> callback;
};
