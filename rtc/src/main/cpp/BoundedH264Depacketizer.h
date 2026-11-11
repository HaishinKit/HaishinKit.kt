#pragma once

#include <rtc/rtc.hpp>
#include <cstddef>
#include <memory>
#include <optional>

// Bound input retention before libdatachannel's video depacketizer assembles an AU.
// An oversized timestamp is discarded until a new timestamp arrives, allowing the
// stream to recover without letting a sender refill the same incomplete AU.
class BoundedH264Depacketizer final : public rtc::MediaHandler {
public:
    explicit BoundedH264Depacketizer(size_t maxBytes = 4 * 1024 * 1024,
                                     size_t maxPackets = 4096)
        : mMaxBytes(maxBytes), mMaxPackets(maxPackets) {}

    void incoming(rtc::message_vector &messages, const rtc::message_callback &send) override {
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
            }
            const auto timestamp = header->timestamp();
            if (!mTimestamp || *mTimestamp != timestamp) {
                reset(); // Discard an incomplete previous access unit rather than emitting it.
                mTimestamp = timestamp;
                mDiscarding = false;
            }
            if (mDiscarding) continue;
            if (mPackets >= mMaxPackets || message->size() > mMaxBytes - mBytes) {
                reset();
                mDiscarding = true;
                requestKeyframe(send);
                continue;
            }
            mBytes += message->size();
            ++mPackets;
            const bool marker = header->marker();
            rtc::message_vector packet{std::move(message)};
            mDepacketizer->incomingChain(packet, send);
            output.insert(output.end(), packet.begin(), packet.end());
            if (marker) reset();
        }
        messages.swap(output);
    }

private:
    void reset() {
        mDepacketizer = std::make_shared<rtc::H264RtpDepacketizer>(rtc::NalUnit::Separator::StartSequence);
        mBytes = 0;
        mPackets = 0;
    }

    const size_t mMaxBytes;
    const size_t mMaxPackets;
    size_t mBytes = 0;
    size_t mPackets = 0;
    std::optional<uint32_t> mTimestamp;
    bool mDiscarding = false;
    std::shared_ptr<rtc::H264RtpDepacketizer> mDepacketizer;
};
