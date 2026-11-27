package com.haishinkit.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.annotation.RestrictTo
import com.haishinkit.graphics.DynamicRangeMode
import com.haishinkit.media.source.Camera2Source
import com.haishinkit.media.source.VideoSource

/**
 * The root container for an offscreen video composition.
 */
abstract class Screen(
    val context: Context,
    val dynamicRangeMode: DynamicRangeMode,
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

    /** Creates the default SDR pipeline. */
    constructor(context: Context) : this(context, DynamicRangeMode.SDR)

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

    /**
     * Attaches a video source to a rendering track.
     *
     * @param track The track identifier used by [VideoScreenObject.track].
     * @param video The source to attach, or `null` to detach the track.
     */
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

    /** Updates camera-specific rotation metadata when binding or replacing a track source. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    protected fun updateCameraOrientation(
        video: VideoScreenObject,
        source: VideoSource?,
    ) {
        video.isBackCamera = source is Camera2Source && source.isBackFacing
    }

    /** Releases source-derived rotation metadata, including videos inside detached containers. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    protected fun clearCameraOrientation(screenObject: ScreenObject) {
        if (screenObject is VideoScreenObject) updateCameraOrientation(screenObject, null)
        if (screenObject is ScreenObjectContainer) {
            screenObject.findByClass(VideoScreenObject::class.java).forEach { updateCameraOrientation(it, null) }
        }
    }

    companion object {
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720

        const val TYPE = "screen"

        /** Select before constructing mixers or streams. Existing screens retain their backend. */
        @Volatile
        var factory: (Context) -> Screen = ::createOpenGL

        /**
         * Creates a screen with a dedicated rendering thread and a default size of 1280 × 720 pixels.
         */
        @JvmOverloads
        fun create(
            context: Context,
            dynamicRangeMode: DynamicRangeMode = DynamicRangeMode.SDR,
        ): Screen =
            if (dynamicRangeMode == DynamicRangeMode.SDR) factory(context) else createOpenGL(context, dynamicRangeMode)

        /** Creates the OpenGL screen without consulting [factory]. */
        @JvmOverloads
        fun createOpenGL(
            context: Context,
            dynamicRangeMode: DynamicRangeMode = DynamicRangeMode.SDR,
        ): Screen {
            require(dynamicRangeMode == DynamicRangeMode.SDR || android.os.Build.VERSION.SDK_INT >= 33) { "HLG rendering requires Android 13 or later" }
            return com.haishinkit.gles.screen.ThreadScreen(context, dynamicRangeMode).apply {
                frame = Rect(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT)
            }
        }
    }
}
