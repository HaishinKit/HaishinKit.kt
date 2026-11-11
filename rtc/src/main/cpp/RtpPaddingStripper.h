#pragma once

#include <rtc/rtc.hpp>
#include <cstddef>

// libdatachannel's audio depacketizer strips the RTP header but not RTP padding.
// Validate the complete header before accessing padding and never deliver padding
// or padding-only probe packets as encoded audio frames.
class RtpPaddingStripper final : public rtc::MediaHandler {
public:
    void incoming(rtc::message_vector &messages, const rtc::message_callback &) override {
        rtc::message_vector output;
        for (auto &message : messages) {
            if (message->type == rtc::Message::Control) {
                output.push_back(std::move(message));
                continue;
            }
            if (message->size() < sizeof(rtc::RtpHeader)) continue;
            const auto *header = reinterpret_cast<const rtc::RtpHeader *>(message->data());
            const auto baseSize = header->getSize();
            if (header->version() != 2 || message->size() < baseSize + (header->extension() ? 4 : 0)) continue;
            const auto headerSize = baseSize + header->getExtensionHeaderSize();
            if (message->size() <= headerSize) continue;
            if (header->padding()) {
                const auto padding = std::to_integer<unsigned char>(message->back());
                if (padding == 0 || padding >= message->size() - headerSize) continue;
                message->resize(message->size() - padding);
                (*message)[0] &= rtc::byte{0xdf};
            }
            output.push_back(std::move(message));
        }
        messages.swap(output);
    }
};
