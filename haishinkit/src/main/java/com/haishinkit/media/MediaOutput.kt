package com.haishinkit.media

import java.lang.ref.WeakReference

/**
 * Receives media buffers from a [MediaOutputDataSource].
 */
interface MediaOutput {
    /**
     * A weak reference to the registered media source, or `null` when detached.
     */
    var dataSource: WeakReference<MediaOutputDataSource>?

    /**
     * Receives a media buffer from the source. Consume or copy its payload before it is reused.
     */
    fun append(buffer: MediaBuffer)
}
