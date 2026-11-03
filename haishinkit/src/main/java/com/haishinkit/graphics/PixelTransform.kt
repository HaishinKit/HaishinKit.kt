package com.haishinkit.graphics

import android.content.Context
import android.util.Size
import android.view.Surface
import com.haishinkit.gles.ThreadPixelTransform
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.screen.Screen

/**
 * Renders a composited [Screen] to an output [Surface].
 */
interface PixelTransform : AutoCloseable {
    /**
     * The current application environment.
     */
    val context: Context

    /**
     * The screen to render, or `null` when no screen is attached.
     */
    var screen: Screen?

    /**
     * The destination surface, or `null` when no output is attached.
     */
    var surface: Surface?

    /**
     * The output dimensions, in pixels.
     */
    var imageExtent: Size

    /**
     * The effect applied to rendered video, such as monochrome or sepia.
     */
    var videoEffect: VideoEffect

    /**
     * How the video is scaled to fit the output surface.
     */
    var videoGravity: VideoGravity

    /**
     * The target output frame rate, in frames per second.
     */
    var frameRate: Int

    /**
     * Specifies the background color.
     */
    var backgroundColor: Int

    /** Releases rendering resources. */
    override fun close() {
        surface = null
        screen = null
    }

    companion object {
        /** Select before creating streams or views. Existing transforms keep their backend. */
        @Volatile
        var factory: (Context) -> PixelTransform = ::createOpenGL

        /** Creates the OpenGL backend, bypassing [factory]. */
        fun createOpenGL(context: Context): PixelTransform = ThreadPixelTransform(context)

        /**
         * Creates a pixel transform instance.
         */
        fun create(context: Context): PixelTransform = factory(context)
    }
}
