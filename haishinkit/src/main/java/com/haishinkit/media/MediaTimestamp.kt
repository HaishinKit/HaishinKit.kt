package com.haishinkit.media

internal data class MediaTimestamp(
    val scale: Long = 1L,
    private var start: Long = DEFAULT_TIMESTAMP,
) {
    val duration: Long
        get() = (nanoTime - start) / scale

    var nanoTime: Long = DEFAULT_TIMESTAMP
        set(value) {
            if (0 < value && start == DEFAULT_TIMESTAMP) {
                start = value
            }
            field = value
        }

    fun clear() {
        nanoTime = DEFAULT_TIMESTAMP
        start = DEFAULT_TIMESTAMP
    }

    companion object {
        private const val DEFAULT_TIMESTAMP = -1L
    }
}
