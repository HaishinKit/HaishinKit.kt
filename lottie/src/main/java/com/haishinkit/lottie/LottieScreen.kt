package com.haishinkit.lottie

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import androidx.annotation.RawRes
import com.airbnb.lottie.AsyncUpdates
import com.airbnb.lottie.ImageAssetDelegate
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieListener
import com.airbnb.lottie.LottieTask
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.Renderer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.zip.ZipInputStream
import kotlin.math.min

/**
 * Renders a Lottie animation as an image in an offscreen video composition.
 *
 * Load an animation with [setAnimation] or [setAnimationFromJson], add this object to a screen,
 * and call [playAnimation]. Animations repeat indefinitely by default.
 *
 * @property context The Android context used to load animation resources.
 * @param id The screen object identifier, or `null` to generate one.
 */
@Suppress("MemberVisibilityCanBePrivate", "UNUSED")
class LottieScreen(
    val context: Context,
    id: String? = null,
) : ImageScreenObject(id) {
    private interface Keys {
        companion object {
            const val ANIMATION_RES_ID = "animationResId"
        }
    }

    override val type: String = TYPE

    /**
     * Whether the underlying Lottie drawable is currently animating.
     */
    val isAnimating: Boolean
        get() = lottieDrawable.isAnimating

    /**
     * Whether merge paths are enabled in the Lottie drawable.
     */
    var enableMergePaths: Boolean
        get() = lottieDrawable.enableMergePathsForKitKatAndAbove()
        set(value) {
            lottieDrawable.enableMergePathsForKitKatAndAbove(value)
        }

    /**
     * The update mode forwarded to the Lottie drawable.
     */
    var asyncUpdates: AsyncUpdates
        get() = lottieDrawable.asyncUpdates
        set(value) {
            lottieDrawable.asyncUpdates = value
        }

    /**
     * Whether opacity is applied to layers by the Lottie drawable.
     */
    var isApplyingOpacityToLayersEnabled: Boolean
        get() = lottieDrawable.isApplyingOpacityToLayersEnabled
        set(value) {
            lottieDrawable.isApplyingOpacityToLayersEnabled = value
        }

    /**
     * Whether the drawable preserves the original bounds of image assets.
     */
    var maintainOriginalImageBounds: Boolean
        get() = lottieDrawable.maintainOriginalImageBounds
        set(value) {
            lottieDrawable.maintainOriginalImageBounds = value
        }

    /**
     * Whether rendering is clipped to the composition bounds.
     */
    var clipToCompositionBounds: Boolean
        get() = lottieDrawable.clipToCompositionBounds
        set(value) {
            lottieDrawable.clipToCompositionBounds = value
        }

    /**
     * Reads the drawable's text clipping setting.
     *
     * The current setter updates composition clipping, the same setting as [clipToCompositionBounds].
     */
    var clipTextToBoundingBox: Boolean
        get() = lottieDrawable.clipTextToBoundingBox
        set(value) {
            lottieDrawable.clipToCompositionBounds = value
        }

    /**
     * The repeat count forwarded to the drawable; defaults to `LottieDrawable.INFINITE`.
     */
    var repeatCount: Int
        get() = lottieDrawable.repeatCount
        set(value) {
            lottieDrawable.repeatCount = value
        }

    /**
     * The asset folder used by the drawable to resolve animation images.
     */
    var imageAssetsFolder: String?
        get() = lottieDrawable.imageAssetsFolder
        set(value) {
            lottieDrawable.setImagesAssetsFolder(value)
        }

    /**
     * The playback speed multiplier forwarded to the Lottie drawable.
     */
    var speed: Float
        get() = lottieDrawable.speed
        set(value) {
            lottieDrawable.speed = value
        }

    override var elements: Map<String, String>
        get() {
            return buildMap {
                put(Keys.ANIMATION_RES_ID, animationResId.toString())
            }
        }
        set(value) {
            value[Keys.ANIMATION_RES_ID]?.let {
                setAnimation(it.toInt())
                playAnimation()
            }
        }

    override var shouldInvalidateLayout: Boolean
        get() = lottieDrawable.isAnimating
        set(value) {
            // no op
        }

    private var canvas: Canvas? = null
    private var cacheComposition = true
    private var animationName: String? = null

    @RawRes
    private var animationResId: Int = 0
    private var composition: LottieComposition? = null
        set(value) {
            field = value
            lottieDrawable.composition = value
            invalidateLayout()
        }
    private var compositionTask: LottieTask<LottieComposition>? = null
        set(value) {
            val result = value?.result
            if (result != null && result.value == composition) return
            field = value?.addListener(loadedListener)?.addFailureListener(failureListener)
        }
    private val lottieDrawable: LottieDrawable by lazy {
        LottieDrawable().apply {
            repeatCount = LottieDrawable.INFINITE
            repeatMode = LottieDrawable.RESTART
        }
    }
    private var failureListener: LottieListener<Throwable>? = null
    private val loadedListener: LottieListener<LottieComposition?> by lazy {
        WeakSuccessListener(
            this,
        )
    }
    private val lottieToBitmapMatrix = Matrix()

    /**
     * Loads an animation from an Android raw resource.
     */
    fun setAnimation(
        @RawRes rawRes: Int,
    ) {
        animationResId = rawRes
        animationName = null
        compositionTask = fromRawRes(rawRes)
    }

    /**
     * Loads an animation from the application assets using the supplied asset name.
     */
    fun setAnimation(assetName: String) {
        animationName = assetName
        animationResId = 0
        compositionTask = fromAssets(assetName)
    }

    /**
     * Loads an animation from a URL using the Lottie composition loader.
     *
     * @param url The animation URL.
     * @param cacheKey A cache key for uncached-loading mode. Ignored while composition caching is enabled,
     * which is the default for this class.
     */
    fun setAnimationFromUrl(
        url: String?,
        cacheKey: String? = null,
    ) {
        val task =
            if (cacheComposition) {
                LottieCompositionFactory.fromUrl(
                    context,
                    url,
                )
            } else {
                LottieCompositionFactory.fromUrl(
                    context,
                    url,
                    cacheKey,
                )
            }
        compositionTask = task
    }

    /**
     * Loads a JSON animation from an input stream.
     *
     * @param stream The JSON input stream passed to the Lottie loader.
     * @param cacheKey The composition cache key, or `null` to load without a cache key.
     */
    fun setAnimation(
        stream: InputStream?,
        cacheKey: String? = null,
    ) {
        compositionTask = LottieCompositionFactory.fromJsonInputStream(stream, cacheKey)
    }

    /**
     * Loads an animation and its assets from a ZIP stream.
     *
     * @param stream The ZIP input stream passed to the Lottie loader.
     * @param cacheKey The composition cache key, or `null` to load without a cache key.
     */
    fun setAnimation(
        stream: ZipInputStream?,
        cacheKey: String? = null,
    ) {
        compositionTask = LottieCompositionFactory.fromZipStream(stream, cacheKey)
    }

    /**
     * Loads an animation from a JSON string.
     *
     * @param jsonString The animation JSON.
     * @param cacheKey The composition cache key, or `null` to load without a cache key.
     */
    fun setAnimationFromJson(
        jsonString: String,
        cacheKey: String? = null,
    ) {
        setAnimation(ByteArrayInputStream(jsonString.toByteArray()), cacheKey)
    }

    /**
     * Starts the Lottie animation and invalidates the screen layout.
     */
    fun playAnimation() {
        lottieDrawable.playAnimation()
        invalidateLayout()
    }

    /**
     * Cancels animation playback through the Lottie drawable.
     */
    fun cancelAnimation() {
        lottieDrawable.cancelAnimation()
    }

    /**
     * Pauses animation playback through the Lottie drawable.
     */
    fun pauseAnimation() {
        lottieDrawable.pauseAnimation()
    }

    /**
     * Sets the delegate used by the Lottie drawable to resolve image assets.
     */
    fun setImageAssetDelegate(assetDelegate: ImageAssetDelegate) {
        lottieDrawable.setImageAssetDelegate(assetDelegate)
    }

    /**
     * Sets the underlying Lottie drawable's safe mode flag.
     */
    fun setSafeMode(safeMode: Boolean) {
        lottieDrawable.setSafeMode(safeMode)
    }

    /**
     * Sets the font-name-to-typeface mapping used by the Lottie drawable.
     */
    fun setFontMap(fontMap: Map<String, Typeface>) {
        lottieDrawable.setFontMap(fontMap)
    }

    @SuppressLint("RestrictedApi")
    override fun layout(renderer: Renderer) {
        super.layout(renderer)
        val composition = composition ?: return
        if (bitmap?.width != bounds.width() || bitmap?.height != bounds.height()) {
            bitmap =
                Bitmap
                    .createBitmap(
                        bounds.width(),
                        bounds.height(),
                        Bitmap.Config.ARGB_8888,
                    ).apply {
                        canvas = Canvas(this)
                    }
        }
        bitmap?.eraseColor(Color.TRANSPARENT)
        val minScale =
            min(
                bounds.width().toFloat() / composition.bounds.width().toFloat(),
                bounds.height().toFloat() / composition.bounds.height().toFloat(),
            )
        lottieToBitmapMatrix.reset()
        lottieToBitmapMatrix.preScale(
            minScale,
            minScale,
        )
        lottieDrawable.bounds.set(0, 0, composition.bounds.width(), composition.bounds.height())
        lottieDrawable.draw(canvas, lottieToBitmapMatrix)
    }

    private fun fromRawRes(
        @RawRes rawRes: Int,
    ): LottieTask<LottieComposition>? =
        if (cacheComposition) {
            LottieCompositionFactory.fromRawRes(
                context,
                rawRes,
            )
        } else {
            LottieCompositionFactory.fromRawRes(
                context,
                rawRes,
                null,
            )
        }

    private fun fromAssets(assetName: String): LottieTask<LottieComposition>? =
        if (cacheComposition) {
            LottieCompositionFactory.fromAsset(
                context,
                assetName,
            )
        } else {
            LottieCompositionFactory.fromAsset(
                context,
                assetName,
                null,
            )
        }

    private class WeakSuccessListener(
        target: LottieScreen,
    ) : LottieListener<LottieComposition?> {
        private val targetReference = WeakReference(target)

        override fun onResult(result: LottieComposition?) {
            val targetScreen = targetReference.get() ?: return
            targetScreen.composition = result
        }
    }

    private class WeakFailureListener(
        target: LottieScreen,
    ) : LottieListener<Throwable> {
        private val targetReference = WeakReference(target)

        override fun onResult(result: Throwable) {
            val targetScreen = targetReference.get() ?: return
            val listener =
                if (targetScreen.failureListener == null) DEFAULT_FAILURE_LISTENER else targetScreen.failureListener
            listener?.onResult(result)
        }
    }

    companion object {
        /**
         * The type identifier used for Lottie screen objects in scene snapshots.
         */
        const val TYPE = "lottie"

        private val TAG = LottieScreen::class.java.simpleName

        private val DEFAULT_FAILURE_LISTENER =
            LottieListener<Throwable> { throwable: Throwable? ->
                throw IllegalStateException("Unable to parse composition", throwable)
            }
    }
}
