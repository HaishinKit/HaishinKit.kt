package com.haishinkit.view

import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.MediaOutput

interface StreamView : MediaOutput {
    /**
     * How the video is scaled to fit its bounds.
     */
    var videoGravity: VideoGravity

    /**
     * The effect applied to rendered video, such as monochrome or sepia.
     */
    var videoEffect: VideoEffect

    /**
     * The target output frame rate, in frames per second.
     */
    var frameRate: Int
}
