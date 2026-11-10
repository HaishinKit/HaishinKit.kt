#include "BoundedH264Depacketizer.h"
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

int main() {
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
    std::cout << "Bounded H264 depacketization tests passed" << std::endl;
}
