package com.haishinkit.gles.screen

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.view.Surface
import com.haishinkit.media.source.VideoSource

/** One renderer-owned external texture for a file decoder, independent of capture-track IDs. */
internal class AssetTexture private constructor(
    val source: VideoSource,
    val id: Int,
    texture: SurfaceTexture,
    surface: Surface,
    onError: (Throwable) -> Unit,
) : AutoCloseable {
    val transform = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }
    private val texture = texture
    private val surface = surface
    private val onError = onError
    private var closed = false

    init {
        texture.setOnFrameAvailableListener {
            if (!closed) {
                try {
                    texture.updateTexImage()
                    texture.getTransformMatrix(transform)
                } catch (cause: RuntimeException) {
                    onError(cause)
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        source.surface = null
        texture.setOnFrameAvailableListener(null)
        surface.release()
        texture.release()
        GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    companion object {
        fun create(
            source: VideoSource,
            onError: (Throwable) -> Unit,
        ): AssetTexture {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            check(ids[0] != 0) { "Could not allocate a video asset texture." }
            var texture: SurfaceTexture? = null
            var surface: Surface? = null
            try {
                texture = SurfaceTexture(ids[0]).apply { setDefaultBufferSize(source.videoSize.width, source.videoSize.height) }
                surface = Surface(texture)
                val input = AssetTexture(source, ids[0], texture, surface, onError)
                source.surface = surface
                return input
            } catch (cause: Throwable) {
                runCatching { source.surface = null }
                runCatching { surface?.release() }
                runCatching { texture?.release() }
                GLES20.glDeleteTextures(1, ids, 0)
                throw cause
            }
        }
    }
}
