package com.haishinkit.media.source

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.getSystemService
import com.haishinkit.BuildConfig
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.media.MediaMixer
import com.haishinkit.screen.VideoScreenObject

/**
 * A video source that captures a display by the MediaProjection API.
 */
@Suppress("UNUSED", "MemberVisibilityCanBePrivate")
class MediaProjectionSource(
    private val context: Context,
    private var mediaProjection: MediaProjection,
) : VideoSource,
    VideoScreenObject.OnSurfaceChangedListener {
    private class Callback(
        val source: MediaProjectionSource,
    ) : MediaProjection.Callback() {
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            super.onCapturedContentVisibilityChanged(isVisible)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Callback#onCapturedContentVisibilityChanged:$isVisible")
            }
        }

        override fun onCapturedContentResize(
            width: Int,
            height: Int,
        ) {
            super.onCapturedContentResize(width, height)
            if (source.isRotatesWithContent) {
                source.video.imageOrientation =
                    if (width < height) {
                        ImageOrientation.UP
                    } else {
                        ImageOrientation.LEFT
                    }
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Callback#onCapturedContentResize:$width:$height")
            }
        }

        override fun onStop() {
            super.onStop()
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Callback#onStop")
            }
        }
    }

    var isRotatesWithContent = true

    override val video: VideoScreenObject by lazy {
        VideoScreenObject().apply {
            listener = this@MediaProjectionSource
        }
    }

    private var virtualDisplay: VirtualDisplay? = null
        set(value) {
            field?.release()
            field = value
        }
    private var handler: Handler? = null
        get() {
            if (field == null) {
                val thread = HandlerThread(TAG)
                thread.start()
                field = Handler(thread.looper)
            }
            return field
        }
        set(value) {
            field?.looper?.quitSafely()
            field = value
        }
    private val callback: Callback by lazy { Callback(this) }
    private val displaySize: Size by lazy { getDisplaySize(context) }

    /**
     * Register a listener to receive notifications about when the MediaProjection changes state.
     */
    fun registerCallback(
        callback: MediaProjection.Callback,
        handler: Handler?,
    ) {
        mediaProjection.registerCallback(callback, handler)
    }

    /**
     * Unregister a MediaProjection listener.
     */
    fun unregisterCallback(callback: MediaProjection.Callback) {
        mediaProjection.unregisterCallback(callback)
    }

    override suspend fun open(mixer: MediaMixer): Result<Unit> {
        // Android 14 must register an callback.
        mediaProjection.registerCallback(callback, null)
        video.videoSize = displaySize
        return Result.success(Unit)
    }

    override suspend fun close() {
        mediaProjection.unregisterCallback(callback)
        mediaProjection.stop()
        virtualDisplay = null
    }

    override fun onSurfaceChanged(surface: Surface?) {
        handler?.post {
            virtualDisplay =
                mediaProjection.createVirtualDisplay(
                    DEFAULT_DISPLAY_NAME,
                    displaySize.width,
                    displaySize.height,
                    context.resources.configuration.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    handler,
                )
        }
    }

    companion object {
        const val DEFAULT_DISPLAY_NAME = "MediaProjectionSourceDisplay"
        private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 128
        private val TAG = MediaProjectionSource::class.java.simpleName

        private fun getDisplaySize(context: Context): Size =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowManager =
                    context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                Size(
                    windowManager.currentWindowMetrics.bounds.width(),
                    windowManager.currentWindowMetrics.bounds.height(),
                )
            } else {
                val point = Point()
                @Suppress("DEPRECATION")
                context
                    .getSystemService<DisplayManager>()
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
                    ?.getRealSize(point)
                Size(point.x, point.y)
            }
    }
}
