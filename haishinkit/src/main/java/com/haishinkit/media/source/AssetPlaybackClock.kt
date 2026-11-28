package com.haishinkit.media.source

/** One timeline shared by the video and audio decoders, including loop boundaries. */
internal class AssetPlaybackClock {
    @Volatile var pass: Pass? = null
        private set

    @Volatile var active = true

    fun begin(
        firstPresentationUs: Long,
        anchorNanos: Long,
    ) {
        pass = Pass(firstPresentationUs, anchorNanos)
    }

    class Pass(val firstPresentationUs: Long, val anchorNanos: Long) {
        /** Ignore ordinary scheduling jitter; discard stale PCM after a material decoder stall. */
        fun expiredFrames(
            presentationUs: Long,
            frames: Int,
            sampleRate: Int,
            nowNanos: Long,
            recovering: Boolean = false,
        ): Int {
            val target = deadline(presentationUs)
            if (nowNanos <= target) return 0
            val late = nowNanos - target
            if (!recovering && late in 0..100_000_000) return 0
            val duration = (frames * 1_000_000_000L + sampleRate - 1) / sampleRate
            if (late < 0 || late >= duration) return frames
            return (late * sampleRate / 1_000_000_000L).toInt()
        }

        fun deadline(presentationUs: Long): Long {
            val delta = presentationUs - firstPresentationUs
            require(((presentationUs xor firstPresentationUs) and (presentationUs xor delta)) >= 0) { "Invalid asset audio timestamp" }
            require(delta in Long.MIN_VALUE / 1000..Long.MAX_VALUE / 1000) { "Invalid asset audio timestamp" }
            val offset = delta * 1000
            val result = anchorNanos + offset
            require(((anchorNanos xor result) and (offset xor result)) >= 0) { "Invalid asset audio timestamp" }
            return result
        }

        fun positionUs(): Long = firstPresentationUs + (System.nanoTime() - anchorNanos).coerceAtLeast(0) / 1000
    }
}
