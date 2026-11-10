package com.haishinkit.rtc

/** Extend a 32-bit RTP clock, preserving reordering across wraparound. */
internal class RTPClock(private val rate: Long) {
    private var previous: Long? = null
    private var ticks = 0L
    private var anchor = 0L

    fun timeUs(
        timestamp: Long,
        arrivalUs: Long,
    ): Long {
        val raw = timestamp and 0xffffffffL
        val last = previous
        if (last == null) {
            previous = raw
            anchor = arrivalUs
            return anchor
        }
        val delta = (raw - last).toInt().toLong()
        val next = ticks + delta
        if (delta >= 0) {
            ticks = next
            previous = raw
        }
        return anchor + next * 1_000_000 / rate
    }
}
