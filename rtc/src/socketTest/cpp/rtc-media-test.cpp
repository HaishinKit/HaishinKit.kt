#include "BoundedH264Depacketizer.h"
#include "RtpPaddingStripper.h"
#include "FirHandler.h"
#include "VpRtp.h"
#include <cstdlib>
#include <iostream>

static void require(bool condition, const char *message) {
    if (!condition) { std::cerr << message << std::endl; std::exit(1); }
}

static rtc::message_ptr packet(uint16_t sequence, uint32_t timestamp, bool marker, size_t payload = 8) {
    auto message = rtc::make_message(sizeof(rtc::RtpHeader) + payload);
    auto header = reinterpret_cast<rtc::RtpHeader *>(message->data());
    header->preparePacket();
    header->setSeqNumber(sequence);
    header->setTimestamp(timestamp);
    header->setMarker(marker);
    header->setPayloadType(96);
    (*message)[sizeof(rtc::RtpHeader)] = rtc::byte{0x65};
    return message;
}

static rtc::message_vector feed(BoundedH264Depacketizer &decoder, rtc::message_ptr packet) {
    rtc::message_vector messages{std::move(packet)};
    decoder.incoming(messages, [](rtc::message_ptr) {});
    return messages;
}

class KeyframeCounter final : public rtc::MediaHandler {
public:
    unsigned requests = 0;
    bool requestKeyframe(const rtc::message_callback &) override { ++requests; return true; }
};

static void vpSharedTimeAndPadding() {
    auto frame = [](uint16_t seq, uint32_t time, rtc::byte value) {
        auto message = packet(seq, time, true, 2);
        (*message)[sizeof(rtc::RtpHeader)] = rtc::byte{0x0c}; // VP9 B/E
        message->back() = value;
        return message;
    };
    VpRtpDepacketizer receiver(true);
    auto feedback = std::make_shared<KeyframeCounter>();
    receiver.setNext(feedback);
    auto hidden = frame(65534, 90, rtc::byte{0x84});
    rtc::message_vector data{hidden};
    receiver.incoming(data, [](rtc::message_ptr) {});
    require(data.size() == 1, "VP9 hidden reference picture lost");
    data = {hidden};
    receiver.incoming(data, [](rtc::message_ptr) {});
    require(data.empty(), "Retransmitted VP9 picture delivered twice");
    data = {frame(65535, 90, rtc::byte{0x86})};
    receiver.incoming(data, [](rtc::message_ptr) {});
    require(data.size() == 1 && data[0]->frameInfo->timestamp == 90, "Visible VP9 picture sharing RTP time lost");
    auto probe = packet(0, 91, false, 4);
    reinterpret_cast<rtc::RtpHeader *>(probe->data())->_first |= 0x20;
    probe->back() = rtc::byte{4};
    data = {probe};
    receiver.incoming(data, [](rtc::message_ptr) {});
    require(data.empty(), "Padding became a video frame");
    data = {frame(1, 92, rtc::byte{0x86})};
    receiver.incoming(data, [](rtc::message_ptr) {});
    require(data.size() == 1 && feedback->requests == 0, "Normal padding caused a spurious PLI");
    data = {frame(3, 93, rtc::byte{0x86})}; // seq 2 really missing
    receiver.incoming(data, [](rtc::message_ptr) {});
    require(feedback->requests == 1, "Actual sequence loss did not request recovery");
}

static void vpMidFrameJoin() {
    for (bool vp9 : {false, true}) for (uint16_t initial : {uint16_t{32768}, uint16_t{40000}, uint16_t{65535}}) {
        VpRtpDepacketizer receiver(vp9);
        auto partial = packet(initial, 100, false, 2);
        (*partial)[sizeof(rtc::RtpHeader)] = rtc::byte{0}; // continuation, no frame start
        rtc::message_vector data{partial};
        receiver.incoming(data, [](rtc::message_ptr) {});
        require(data.empty(), "Mid-frame join emitted incomplete picture");
        auto key = packet(uint16_t(initial + 1), 200, true, 2);
        (*key)[sizeof(rtc::RtpHeader)] = vp9 ? rtc::byte{0x0c} : rtc::byte{0x10};
        key->back() = vp9 ? rtc::byte{0x82} : rtc::byte{0x10};
        data = {key};
        receiver.incoming(data, [](rtc::message_ptr) {});
        require(data.size() == 1, "High initial sequence poisoned next keyframe");
    }
}

static void vpLongGapAndProbing() {
    for (bool vp9 : {false, true}) {
        auto key = [vp9](uint16_t seq, uint32_t time) {
            auto message = packet(seq, time, true, 2);
            (*message)[sizeof(rtc::RtpHeader)] = vp9 ? rtc::byte{0x0c} : rtc::byte{0x10};
            message->back() = vp9 ? rtc::byte{0x82} : rtc::byte{0x10};
            return message;
        };
        VpRtpDepacketizer receiver(vp9);
        auto original = key(100, 100);
        rtc::message_vector messages{original};
        receiver.incoming(messages, [](rtc::message_ptr) {});
        require(messages.size() == 1, "Initial picture missing");
        messages = {key(40000, 200)};
        receiver.incoming(messages, [](rtc::message_ptr) {});
        require(messages.size() == 1, "Large sequence gap blocked a new keyframe");
        messages = {original};
        receiver.incoming(messages, [](rtc::message_ptr) {});
        require(messages.empty(), "Consumed old picture replayed after a large gap");
        VpRtpDepacketizer probing(vp9);
        messages = {key(10, 100)};
        probing.incoming(messages, [](rtc::message_ptr) {});
        // Probe 11 was lost, but the active SRTP transport continues receiving probes.
        for (unsigned seq = 12; seq < 40014; ++seq) {
            auto probe = packet(uint16_t(seq), 200, false, 4);
            reinterpret_cast<rtc::RtpHeader *>(probe->data())->_first |= 0x20;
            probe->back() = rtc::byte{4};
            messages = {probe};
            probing.incoming(messages, [](rtc::message_ptr) {});
            require(messages.empty(), "Probe produced a picture");
        }
        messages = {key(40014, 300)};
        probing.incoming(messages, [](rtc::message_ptr) {});
        require(messages.size() == 1, "Missing first probe poisoned later video");
    }
}

static void vpTests() {
    vpSharedTimeAndPadding();
    vpMidFrameJoin();
    vpLongGapAndProbing();
    for (bool vp9 : {false, true}) {
        auto config = std::make_shared<rtc::RtpPacketizationConfig>(42, "test", 96, 90000);
        config->sequenceNumber = 65534;
        VpRtpPacketizer encoder(config, vp9, 20);
        rtc::binary source(80, rtc::byte{0x24});
        source[0] = vp9 ? rtc::byte{0x82} : rtc::byte{0x10};
        rtc::message_vector packets{rtc::make_message(rtc::binary(source), std::make_shared<rtc::FrameInfo>(90000))};
        encoder.outgoing(packets, [](rtc::message_ptr) {});
        require(packets.size() > 3, "VP frame not fragmented");
        auto originals = packets;
        VpRtpDepacketizer decoder(vp9);
        decoder.incoming(packets, [](rtc::message_ptr) {});
        require(packets.size() == 1 && rtc::binary(*packets[0]) == source, "VP fragmented frame roundtrip mismatch");
        require(packets[0]->frameInfo && packets[0]->frameInfo->timestamp == 90000, "VP timestamp not preserved");
        // Missing middle fragments must not produce a partial frame.
        VpRtpDepacketizer loss(vp9);
        rtc::message_vector damaged{originals.front(), originals.back()};
        loss.incoming(damaged, [](rtc::message_ptr) {});
        require(damaged.empty(), "Lost VP fragment produced a partial frame");
        // Retention is bounded by both byte and packet counts.
        VpRtpDepacketizer bounded(vp9, 30, 2);
        auto oversized = originals;
        bounded.incoming(oversized, [](rtc::message_ptr) {});
        require(oversized.empty(), "VP AU exceeded bounds");
        config->timestamp = 93000;
        rtc::message_vector next{rtc::make_message(rtc::binary(source), std::make_shared<rtc::FrameInfo>(93000))};
        encoder.outgoing(next, [](rtc::message_ptr) {});
        loss.incoming(next, [](rtc::message_ptr) {});
        require(next.size() == 1, "VP did not recover on next timestamp");
        auto control = rtc::make_message(4, rtc::Message::Control);
        rtc::message_vector controls{control};
        decoder.incoming(controls, [](rtc::message_ptr) {});
        require(controls.size() == 1 && controls.front() == control, "VP dropped RTCP");
    }
    // Optional descriptors (long PictureID, layer indices, scalability structure)
    // must be skipped without exposing them as encoded data.
    auto optional = [](bool vp9, std::initializer_list<uint8_t> descriptor) {
        VpRtpDepacketizer decoder(vp9);
        auto message = packet(1, 7, true, descriptor.size() + 1);
        size_t offset = sizeof(rtc::RtpHeader);
        for (auto value : descriptor) (*message)[offset++] = rtc::byte(value);
        (*message)[offset] = rtc::byte{0x82};
        rtc::message_vector messages{message};
        decoder.incoming(messages, [](rtc::message_ptr) {});
        require(messages.size() == 1 && messages[0]->size() == 1 && (*messages[0])[0] == rtc::byte{0x82}, "VP optional descriptor leaked");
        // Every truncation must fail closed, including extension-only payloads.
        for (size_t n = 0; n <= descriptor.size(); ++n) {
            auto truncated = std::make_shared<rtc::Message>(*message);
            truncated->resize(sizeof(rtc::RtpHeader) + n);
            VpRtpDepacketizer fresh(vp9);
            rtc::message_vector values{truncated};
            fresh.incoming(values, [](rtc::message_ptr) {});
            require(values.empty(), "Truncated VP descriptor produced output");
        }
    };
    optional(false, {0x90, 0xf0, 0x80, 0x12, 0x23, 0x00});
    optional(true, {0xae, 0x80, 0x12, 0x00, 0x23, 0x18, 0x00, 0x80, 0x00, 0x60, 1, 0x04, 1});
}

int main() {
    vpTests();
    int keyframes = 0;
    FirHandler fir([&] { ++keyframes; });
    auto feedback = rtc::make_message(20, rtc::Message::Control);
    (*feedback)[0] = rtc::byte{0x84}; (*feedback)[1] = rtc::byte{206};
    (*feedback)[2] = rtc::byte{0}; (*feedback)[3] = rtc::byte{4};
    rtc::message_vector feedbacks{feedback};
    fir.incoming(feedbacks, [](rtc::message_ptr) {});
    require(keyframes == 1, "WebRTC FIR did not request a keyframe");
    feedback->resize(12);
    fir.incoming(feedbacks, [](rtc::message_ptr) {});
    require(keyframes == 1, "Truncated FIR triggered a keyframe");

    // Holding a timestamp fixed without a marker must not retain unlimited data.
    BoundedH264Depacketizer bytes(64, 4096);
    for (int i = 0; i < 10000; ++i) {
        require(feed(bytes, packet(i, 1, false, 40)).empty(), "Oversized AU produced output");
    }
    require(feed(bytes, packet(10000, 1, true)).empty(), "Discarded timestamp was refilled");
    auto recovered = feed(bytes, packet(10001, 2, true));
    require(recovered.size() == 1 && recovered[0]->size() == 12, "No recovery after byte overflow");

    BoundedH264Depacketizer packets(4096, 2);
    require(feed(packets, packet(1, 1, false)).empty(), "Unexpected partial frame");
    require(feed(packets, packet(2, 1, false)).empty(), "Unexpected partial frame");
    require(feed(packets, packet(3, 1, true)).empty(), "Packet cap did not discard AU");
    require(feed(packets, packet(4, 2, true)).size() == 1, "No recovery after packet overflow");

    BoundedH264Depacketizer incomplete;
    feed(incomplete, packet(1, 1, false));
    recovered = feed(incomplete, packet(2, 2, true));
    require(recovered.size() == 1 && recovered[0]->size() == 12, "Previous incomplete AU leaked into next frame");

    // A truncated extension must be rejected before reading its length field.
    auto truncated = rtc::make_message(sizeof(rtc::RtpHeader));
    auto header = reinterpret_cast<rtc::RtpHeader *>(truncated->data());
    header->preparePacket();
    header->setExtension(true);
    require(feed(incomplete, truncated).empty(), "Truncated extension was accepted");
    auto control = rtc::make_message(4, rtc::Message::Control);
    auto forwarded = feed(incomplete, control);
    require(forwarded.size() == 1 && forwarded[0] == control, "RTCP control packet was not forwarded");
    auto opus = std::make_shared<rtc::OpusRtpDepacketizer>();
    opus->addToChain(std::make_shared<RtpPaddingStripper>());
    auto paddedOpus = [](size_t extensionBytes) {
        auto message = packet(1, 1, true, extensionBytes + 7);
        auto header = reinterpret_cast<rtc::RtpHeader *>(message->data());
        header->_first |= 0x20;
        if (extensionBytes) {
            header->setExtension(true);
            auto extension = header->getExtensionHeader();
            extension->setProfileSpecificId(0xbede);
            extension->setHeaderLength(1);
        }
        const auto offset = sizeof(rtc::RtpHeader) + extensionBytes;
        (*message)[offset] = rtc::byte{0xf8};
        (*message)[offset + 1] = rtc::byte{0xff};
        (*message)[offset + 2] = rtc::byte{0xfe};
        message->back() = rtc::byte{4};
        return message;
    };
    for (size_t extensionBytes : {size_t{0}, size_t{8}}) {
        rtc::message_vector audio{paddedOpus(extensionBytes)};
        opus->incomingChain(audio, [](rtc::message_ptr) {});
        require(audio.size() == 1 && audio[0]->size() == 3, "Opus padding reached the audio decoder");
        require((*audio[0])[0] == rtc::byte{0xf8} && audio[0]->back() == rtc::byte{0xfe}, "Opus payload changed");
    }
    auto paddingOnly = packet(1, 1, true, 4);
    reinterpret_cast<rtc::RtpHeader *>(paddingOnly->data())->_first |= 0x20;
    paddingOnly->back() = rtc::byte{4};
    rtc::message_vector audio{paddingOnly};
    opus->incomingChain(audio, [](rtc::message_ptr) {});
    require(audio.empty(), "Padding-only probe became an Opus frame");
    for (auto invalidPadding : {rtc::byte{0}, rtc::byte{255}}) {
        auto invalid = paddedOpus(0);
        invalid->back() = invalidPadding;
        audio = {invalid};
        opus->incomingChain(audio, [](rtc::message_ptr) {});
        require(audio.empty(), "Invalid RTP padding was accepted");
    }
    audio = {truncated};
    opus->incomingChain(audio, [](rtc::message_ptr) {});
    require(audio.empty(), "Truncated Opus RTP header was accepted");
    audio = {control};
    opus->incomingChain(audio, [](rtc::message_ptr) {});
    require(audio.size() == 1 && audio[0] == control, "Audio RTCP control packet was not forwarded");
    std::cout << "Bounded H264 and Opus RTP padding tests passed" << std::endl;
}
