package com.haishinkit.media.source

import com.haishinkit.screen.VideoScreenObject

/**
 * An interface that captures a video source.
 *
 * Use this for creating video sources that depend on the device, such as Camera or MediaProjection.
 * For static images and similar sources, please use the Screen object.
 */
interface VideoSource : Source {
    /**
     * The video screen container object.
     */
    val video: VideoScreenObject
}
