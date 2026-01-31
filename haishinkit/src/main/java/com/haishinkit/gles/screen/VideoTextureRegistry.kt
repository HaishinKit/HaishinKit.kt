package com.haishinkit.gles.screen

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import com.haishinkit.BuildConfig
import com.haishinkit.media.source.VideoSource

internal class VideoTextureRegistry : SurfaceTexture.OnFrameAvailableListener {
    private var textureIds = mutableMapOf<Int, Int>()
    private var genTextures = intArrayOf(0)
    private var surfaceTextures = mutableMapOf<Int, SurfaceTexture>()

    fun getTextureIdByTrack(track: Int): Int? {
        return textureIds[track]
    }

    fun register(
        track: Int,
        video: VideoSource,
    ) {
        GLES20.glGenTextures(1, genTextures, 0)
        val id = genTextures[0]
        textureIds[track] = id
        surfaceTextures[track] =
            SurfaceTexture(id).apply {
                setDefaultBufferSize(
                    video.videoSize.width,
                    video.videoSize.height,
                )
                setOnFrameAvailableListener(this@VideoTextureRegistry)
                video.surface = Surface(this)
            }
    }

    fun unregister(track: Int) {
        val id = textureIds[track]
        surfaceTextures.remove(id)?.let {
            it.setOnFrameAvailableListener(null)
            it.release()
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        try {
            surfaceTexture?.updateTexImage()
        } catch (e: RuntimeException) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "", e)
            }
        }
    }

    private companion object {
        private val TAG = VideoTextureRegistry::class.java.simpleName
    }
}
