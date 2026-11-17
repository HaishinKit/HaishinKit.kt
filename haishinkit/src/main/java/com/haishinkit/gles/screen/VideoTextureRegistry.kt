package com.haishinkit.gles.screen

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import com.haishinkit.BuildConfig
import com.haishinkit.media.source.VideoSource

internal class VideoTextureRegistry : SurfaceTexture.OnFrameAvailableListener {
    private val textures = mutableMapOf<Int, VideoTexture>()

    fun get(track: Int): VideoTexture? = textures[track]

    fun register(
        track: Int,
        video: VideoSource,
    ): VideoTexture {
        unregister(track)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texture =
            SurfaceTexture(ids[0]).apply {
                setDefaultBufferSize(video.videoSize.width, video.videoSize.height)
                setOnFrameAvailableListener(this@VideoTextureRegistry)
            }
        val entry = VideoTexture(ids[0], texture, Surface(texture), video)
        textures[track] = entry
        video.surface = entry.surface
        return entry
    }

    fun unregister(track: Int) {
        val entry = textures.remove(track) ?: return
        entry.surface.release()
        entry.texture.setOnFrameAvailableListener(null)
        entry.texture.release()
        GLES20.glDeleteTextures(1, intArrayOf(entry.id), 0)
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

    class VideoTexture(
        val id: Int,
        val texture: SurfaceTexture,
        val surface: Surface,
        val source: VideoSource,
    )

    private companion object {
        private val TAG = VideoTextureRegistry::class.java.simpleName
    }
}
