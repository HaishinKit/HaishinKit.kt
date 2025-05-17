package com.haishinkit.view

import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaOutput

interface StreamView : MediaOutput {
    /**
     * Specifies the videoGravity how the displays the visual content.
     */
    var videoGravity: VideoGravity

    /**
     * Specifies the videoEffect such as a monochrome, a sepia.
     */
    var videoEffect: VideoEffect

    /**
     * Specifies the frameRate for an output source in frames/sec.
     */
    var frameRate: Int

    override fun append(buffer: MediaBuffer) {
    }
}
