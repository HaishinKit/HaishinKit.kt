package com.haishinkit.graphics

import android.content.Context
import android.util.Size
import android.view.Surface
import com.haishinkit.gles.ThreadPixelTransform
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.screen.Screen

/**
 * The PixelTransform interface provides some graphics operations.
 */
interface PixelTransform {
    /**
     * The current application environment.
     */
    val context: Context

    /**
     * Specifies the off screen object.
     */
    var screen: Screen?

    /**
     * Specifies the surface that is an output source.
     */
    var surface: Surface?

    /**
     * Specifies the current width and height of the output surface.
     */
    var imageExtent: Size

    /**
     * Specifies the videoEffect such as a monochrome, a sepia.
     */
    var videoEffect: VideoEffect

    /**
     * Specifies the videoGravity how the displays the inputSurface's visual content.
     */
    var videoGravity: VideoGravity

    /**
     * Specifies the frameRate for an output source in frames/sec.
     */
    var frameRate: Int

    /**
     * Specifies the background color.
     */
    var backgroundColor: Int

    companion object {
        /**
         * Creates a pixel transform instance.
         */
        fun create(context: Context): PixelTransform = ThreadPixelTransform(context)
    }
}
