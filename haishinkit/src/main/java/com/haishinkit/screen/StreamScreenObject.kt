package com.haishinkit.screen

import android.content.Context
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
            pixelTransform.surface = surface
        }

    private var stream: Stream? = null
    private val pixelTransform: PixelTransform by lazy { PixelTransform.create(context) }

    /**
     * Attach a stream object.
     */
    fun attachStream(stream: Stream?) {
        this.stream = stream
        pixelTransform.surface = surface
        pixelTransform.screen = stream?.screen
    }
}
