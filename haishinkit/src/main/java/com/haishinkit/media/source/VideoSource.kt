package com.haishinkit.media.source

import com.haishinkit.screen.ScreenObjectContainer

/**
 * An interface that captures a video source.
 */
interface VideoSource : Source {
    /**
     * The video screen container object.
     */
    val screen: ScreenObjectContainer
}
