package com.haishinkit.media.source

import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.ImageOrientation

/**
 * An interface that captures a video source.
 *
 * Use this for creating video sources that depend on the device, such as Camera or MediaProjection.
 * For static images and similar sources, please use the Screen object.
 */
interface VideoSource : Source {
    /**
     * The surface used as the output destination for video frames.
     *
     * This surface may be null when the video source is not initialized
     * or has already been released.
     */
    var surface: Surface?

    /**
     * The resolution of the video frames produced by this source.
     */
    val videoSize: Size

    /**
     * The orientation of the video image.
     *
     * This value describes how the image should be rotated or mirrored
     * when rendered or encoded.
     */
    val imageOrientation: ImageOrientation
}
