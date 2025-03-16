package com.haishinkit.screen

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.media.Stream

/**
 * An object that manages offscreen rendering a streaming video track source.
 */
@Suppress("MemberVisibilityCanBePrivate")
class StreamScreenObject(context: Context) : VideoScreenObject() {
    override var surface: Surface?
        get() = super.surface
        set(value) {
            super.surface = value
            stream?.videoCodec?.surface = value
        }

    private var stream: Stream? = null

    /**
     * Attach a stream object.
     */
    fun attachStream(stream: Stream?) {
        this.stream = stream
        stream?.videoCodec?.surface = surface
        videoSize = Size(1000, 1000)
        Log.e("TAG", surface?.toString() ?: "null")
    }
}
