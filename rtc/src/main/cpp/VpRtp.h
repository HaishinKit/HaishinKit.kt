#pragma once

#include <rtc/rtc.hpp>
#include <algorithm>
#include <optional>
#include <stdexcept>

// RFC 7741 / RFC 9628. Packetize whole encoded frames without adding a codec.
// VP9 is profile 0, one spatial layer, without flexible-mode references.
class VpRtpPacketizer final : public rtc::RtpPacketizer {
public:
    VpRtpPacketizer(std::shared_ptr<rtc::RtpPacketizationConfig> config, bool vp9,
                    size_t fragmentSize = 1100)
        : rtc::RtpPacketizer(std::move(config)), mVp9(vp9), mFragmentSize(fragmentSize) {
        if (fragmentSize < 2) throw std::invalid_argument("VP fragment size is too small");
    }
protected:
    std::vector<rtc::binary> fragment(rtc::binary data) override {
        std::vector<rtc::binary> result;
        if (data.empty()) return result;
        const auto first = std::to_integer<uint8_t>(data.front());
        if (mVp9 && (first & 0xf0) != 0x80) throw std::invalid_argument("RTC VP9 requires profile 0");
        const bool predicted = mVp9 && (first & 0x0c) != 0;
        for (size_t offset = 0; offset < data.size();) {
            const auto size = std::min(mFragmentSize - 1, data.size() - offset);
            const bool begin = offset == 0, end = offset + size == data.size();
            rtc::binary payload;
            payload.reserve(size + 1);
            payload.push_back(rtc::byte(mVp9 ? (predicted ? 0x40 : 0) | (begin ? 8 : 0) | (end ? 4 : 0) : (begin ? 0x10 : 0)));
            payload.insert(payload.end(), data.begin() + offset, data.begin() + offset + size);
            result.push_back(std::move(payload));
            offset += size;
        }
        return result;
    }
private:
    const bool mVp9;
    const size_t mFragmentSize;
};

// Retain at most one bounded AU. Missing/reordered new fragments drop that
// timestamp and request a keyframe; already consumed packet duplicates are ignored.
class VpRtpDepacketizer final : public rtc::MediaHandler {
public:
    explicit VpRtpDepacketizer(bool vp9, size_t maxBytes = 4 * 1024 * 1024,
                              size_t maxPackets = 4096)
        : mVp9(vp9), mMaxBytes(maxBytes), mMaxPackets(maxPackets) {}

    void incoming(rtc::message_vector &messages, const rtc::message_callback &send) override {
        rtc::message_vector output;
        for (auto &message : messages) {
            if (message->type == rtc::Message::Control) {
                output.push_back(std::move(message));
                continue;
            }
            if (message->size() < sizeof(rtc::RtpHeader)) continue;
            const auto *header = reinterpret_cast<const rtc::RtpHeader *>(message->data());
            const auto base = header->getSize();
            if (header->version() != 2 || header->payloadType() != 96 || message->size() < base + (header->extension() ? 4 : 0)) continue;
            const auto start = base + header->getExtensionHeaderSize();
            size_t end = message->size();
            if (start >= end) continue;
            if (header->padding()) {
                const auto padding = std::to_integer<uint8_t>(message->back());
                if (padding == 0 || padding > end - start) continue;
                end -= padding;
            }
            // Padding probes advance sequence between complete pictures too.
            if (end == start) {
                if (mHaveSequence && header->seqNumber() == mNextSequence) ++mNextSequence;
                continue;
            }
            if (mHaveSequence && uint16_t(header->seqNumber() - mNextSequence) >= 0x8000) continue;
            const auto timestamp = header->timestamp();
            if (!mTimestamp || *mTimestamp != timestamp) {
                if (mHaveSequence && (mStarted || header->seqNumber() != mNextSequence)) requestKeyframe(send);
                mTimestamp = timestamp;
                mDiscarding = false;
                mData.clear();
                mPackets = 0;
                mStarted = false;
                mCompleted = false;
            }
            if (mDiscarding) continue;
            if (mCompleted) {
                // A VP9 hidden reference picture may share a timestamp with the
                // next picture. Retransmitted old sequence numbers are not new pictures.
                const auto distance = uint16_t(header->seqNumber() - mNextSequence);
                if (distance >= 0x8000) continue;
                if (!mVp9) continue;
                if (distance != 0) { discard(send, header->seqNumber()); continue; }
                mCompleted = false;
            }
            size_t offset = start;
            bool begin = false, finish = false;
            if (!descriptor(*message, end, offset, begin, finish) || offset >= end ||
                (mVp9 && finish != bool(header->marker())) ||
                (!mStarted && !begin) || (mStarted && (begin || header->seqNumber() != mNextSequence)) ||
                mPackets >= mMaxPackets || end - offset > mMaxBytes - mData.size()) {
                discard(send, header->seqNumber());
                continue;
            }
            mStarted = true;
            mNextSequence = uint16_t(header->seqNumber() + 1);
            mHaveSequence = true;
            ++mPackets;
            mData.insert(mData.end(), message->begin() + offset, message->begin() + end);
            if (header->marker()) {
                auto info = std::make_shared<rtc::FrameInfo>(timestamp);
                info->payloadType = header->payloadType();
                output.push_back(rtc::make_message(std::move(mData), info));
                mData.clear();
                mPackets = 0;
                mStarted = false;
                mCompleted = true;
            }
        }
        messages.swap(output);
    }
private:
    bool descriptor(const rtc::binary &data, size_t end, size_t &offset, bool &begin, bool &finish) const {
        auto read = [&](uint8_t &value) {
            if (offset >= end) return false;
            value = std::to_integer<uint8_t>(data[offset++]);
            return true;
        };
        uint8_t flags, value;
        if (!read(flags)) return false;
        if (!mVp9) {
            begin = (flags & 0x10) && (flags & 7) == 0;
            if (flags & 0x80) {
                uint8_t extended;
                if (!read(extended)) return false;
                if (extended & 0x80) {
                    if (!read(value)) return false;
                    if ((value & 0x80) && !read(value)) return false;
                }
                if ((extended & 0x40) && !read(value)) return false;
                if ((extended & 0x30) && !read(value)) return false;
            }
            return true;
        }
        begin = flags & 8;
        finish = flags & 4;
        if (flags & 0x80) {
            if (!read(value)) return false;
            if ((value & 0x80) && !read(value)) return false;
        }
        if (flags & 0x20) {
            if (!read(value) || (value & 0x0f) != 0) return false; // SID=0, D=0
            if (!(flags & 0x10) && !read(value)) return false;
        }
        if (flags & 0x10) {
            if (!(flags & 0x80)) return false;
            if (flags & 0x40) {
                unsigned count = 0;
                do {
                    if (!read(value) || (value >> 1) == 0 || ++count > 3) return false;
                } while (value & 1);
            }
        }
        if (flags & 2) {
            uint8_t ss;
            if (!read(ss) || (ss & 0xe0)) return false; // single spatial layer
            if (ss & 0x10) {
                for (int i = 0; i < 4; ++i) if (!read(value)) return false;
            }
            if (ss & 8) {
                uint8_t groups;
                if (!read(groups)) return false;
                for (unsigned i = 0; i < groups; ++i) {
                    if (!read(value)) return false;
                    const unsigned refs = (value >> 2) & 3;
                    for (unsigned j = 0; j < refs; ++j) if (!read(value)) return false;
                }
            }
        }
        return true;
    }

    void discard(const rtc::message_callback &send, uint16_t sequence) {
        mNextSequence = uint16_t(sequence + 1);
        mHaveSequence = true;
        mData.clear();
        mDiscarding = true;
        mStarted = false;
        requestKeyframe(send);
    }

    const bool mVp9;
    const size_t mMaxBytes;
    const size_t mMaxPackets;
    rtc::binary mData;
    std::optional<uint32_t> mTimestamp;
    uint16_t mNextSequence = 0;
    bool mHaveSequence = false;
    size_t mPackets = 0;
    bool mStarted = false;
    bool mCompleted = false;
    bool mDiscarding = false;
};
