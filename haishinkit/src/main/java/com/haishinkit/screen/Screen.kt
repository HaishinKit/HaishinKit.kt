package com.haishinkit.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

/**
 * An object that manages offscreen rendering a foundation.
 */
abstract class Screen(
    val context: Context,
) : ScreenObjectContainer() {
    /**
     * Callbacks for a screen.
     */
    abstract class Callback {
        /**
         * Invoked immediately after layout frame.
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
     * Binds the gpu texture.
     */
    abstract fun bind(screenObject: ScreenObject)

    /***
     * Unbinds the gpu texture.
     */
    abstract fun unbind(screenObject: ScreenObject)

    /**
     * Registers a listener to receive notifications about when the Screen.
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
