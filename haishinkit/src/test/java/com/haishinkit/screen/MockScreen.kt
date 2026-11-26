package com.haishinkit.screen

import android.content.Context
import android.graphics.Bitmap
import com.haishinkit.media.source.VideoSource

class MockScreen(applicationContext: Context) : Screen(applicationContext) {
    fun updateCameraSource(
        video: VideoScreenObject,
        source: VideoSource?,
    ) {
        updateCameraOrientation(video, source)
    }

    override fun attachVideo(
        track: Int,
        video: VideoSource?,
    ) {
    }

    override fun readPixels(lambda: (bitmap: Bitmap?) -> Unit) {
    }

    override fun bind(screenObject: ScreenObject) {
    }

    override fun unbind(screenObject: ScreenObject) {
    }
}
