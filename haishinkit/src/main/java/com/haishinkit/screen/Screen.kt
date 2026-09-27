package com.haishinkit.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.haishinkit.media.source.VideoSource

/**
 * The root container for an offscreen video composition.
 */
abstract class Screen(
    val context: Context,
) : ScreenObjectContainer() {
    /**
     * Callbacks for a screen.
     */
    abstract class Callback {
        /**
         * Called after a frame has been laid out.
         */
        abstract fun onEnterFrame()
    }

    override val type: String = TYPE

    /**
     * Specifies the screen's background color.
     */
    open var backgroundColor: Int = Color.BLACK

    protected var callbacks = mutableListOf<Callback>()

    /**
     * Reads the pixels of a displayed image.
     */
    abstract fun readPixels(lambda: ((bitmap: Bitmap?) -> Unit))

    /**
     * Binds GPU resources for the screen object.
     */
    abstract fun bind(screenObject: ScreenObject)

    /***
     * Releases the GPU binding for the screen object.
     */
    abstract fun unbind(screenObject: ScreenObject)

    abstract fun attachVideo(
        track: Int,
        video: VideoSource?,
    )

    /**
     * Registers a callback for frame layout notifications. Duplicate registrations are ignored.
     */
    open fun registerCallback(callback: Callback) {
        if (!callbacks.contains(callback)) {
            callbacks.add(callback)
        }
    }

    /**
     * Unregisters a screen listener.
     */
    open fun unregisterCallback(callback: Callback) {
        if (callbacks.contains(callback)) {
            callbacks.remove(callback)
        }
    }

    companion object {
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720

        const val TYPE = "screen"

        fun create(context: Context): Screen =
            com.haishinkit.gles.screen.ThreadScreen(context).apply {
                frame = Rect(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT)
            }
    }
}
