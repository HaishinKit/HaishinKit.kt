package com.haishinkit.gles

import android.content.Context
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.screen.Screen
import java.lang.ref.WeakReference

internal class ThreadPixelTransform(
    override val applicationContext: Context,
) : PixelTransform {
    override var screen: Screen?
        get() = pixelTransform.screen
        set(value) {
            handler.apply {
                sendMessage(obtainMessage(MSG_SET_SCREEN, value))
            }
        }
    override var surface: Surface?
        get() = pixelTransform.surface
        set(value) {
            handler.apply {
                sendMessage(obtainMessage(MSG_SET_OUTPUT_SURFACE, value))
            }
        }
    override var videoGravity: VideoGravity
        get() = pixelTransform.videoGravity
        set(value) {
            handler.apply {
                sendMessage(obtainMessage(MSG_SET_VIDEO_GRAVITY, value))
            }
        }
    override var videoEffect: VideoEffect
        get() = pixelTransform.videoEffect
        set(value) {
            handler.apply {
                sendMessage(obtainMessage(MSG_SET_VIDEO_EFFECT, value))
            }
        }
    override var imageExtent: Size
        get() = pixelTransform.imageExtent
        set(value) {
            handler.apply {
                sendMessage(obtainMessage(MSG_SET_IMAGE_EXTENT, value))
            }
        }
    override var frameRate: Int
        get() = pixelTransform.frameRate
        set(value) {
            handler.apply {
                sendMessage(obtainMessage(MSG_SET_FRAME_RATE, value))
            }
        }

    private val handler: Handler by lazy {
        val thread = HandlerThread(TAG)
        thread.start()
        Handler(pixelTransform, thread.looper)
    }

    private val pixelTransform: com.haishinkit.gles.PixelTransform by lazy {
        PixelTransform(applicationContext)
    }

    private class Handler(
        frame: com.haishinkit.gles.PixelTransform,
        looper: Looper,
    ) : android.os.Handler(looper) {
        private val weakTransform =
            WeakReference(frame)

        override fun handleMessage(message: Message) {
            val transform = weakTransform.get() ?: return
            when (message.what) {
                MSG_SET_SCREEN -> {
                    if (message.obj == null) {
                        transform.screen = null
                    } else {
                        transform.screen = message.obj as Screen
                    }
                }

                MSG_SET_OUTPUT_SURFACE -> {
                    if (message.obj == null) {
                        transform.surface = null
                    } else {
                        transform.surface = message.obj as Surface
                    }
                }

                MSG_SET_VIDEO_GRAVITY -> {
                    transform.videoGravity = message.obj as VideoGravity
                }

                MSG_SET_IMAGE_EXTENT -> {
                    transform.imageExtent = message.obj as Size
                }

                MSG_SET_VIDEO_EFFECT -> {
                    transform.videoEffect = message.obj as VideoEffect
                }

                MSG_SET_FRAME_RATE -> {
                    transform.frameRate = message.obj as Int
                }

                else -> throw RuntimeException("Unhandled msg what=$message.what")
            }
        }
    }

    protected fun finalize() {
        surface = null
        screen = null
        handler.looper.quitSafely()
    }

    companion object {
        private const val MSG_SET_OUTPUT_SURFACE = 0
        private const val MSG_SET_SCREEN = 1
        private const val MSG_SET_VIDEO_GRAVITY = 2
        private const val MSG_SET_IMAGE_EXTENT = 3
        private const val MSG_SET_VIDEO_EFFECT = 4
        private const val MSG_SET_FRAME_RATE = 5

        private val TAG = ThreadPixelTransform::class.java.simpleName
    }
}
