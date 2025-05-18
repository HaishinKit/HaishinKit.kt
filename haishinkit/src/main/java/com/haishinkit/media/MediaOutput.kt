package com.haishinkit.media

import java.lang.ref.WeakReference

/**
 * Callbacks for the media data.
 */
interface MediaOutput {
    /**
     * The source of the media data object.
     */
    var dataSource: WeakReference<MediaOutputDataSource>?

    /**
     * Invoked immediately after capture data.
     */
    fun append(buffer: MediaBuffer)
}
