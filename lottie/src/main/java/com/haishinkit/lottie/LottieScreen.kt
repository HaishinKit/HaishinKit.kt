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
 * An object that manages offscreen rendering a lottie source.
 */
@Suppress("MemberVisibilityCanBePrivate", "UNUSED")
class LottieScreen(
    val context: Context,
) : ImageScreenObject() {
    /**
     * Wrapper for LottieDrawable#isAnimating.
     */
    val isAnimating: Boolean
        get() = lottieDrawable.isAnimating

    /**
     * Wrapper for LottieDrawable#enableMergePaths.
     */
    var enableMergePaths: Boolean
        get() = lottieDrawable.enableMergePathsForKitKatAndAbove()
        set(value) {
            lottieDrawable.enableMergePathsForKitKatAndAbove(value)
        }

    /**
     * Wrapper for LottieDrawable#asyncUpdates.
     */
    var asyncUpdates: AsyncUpdates
        get() = lottieDrawable.asyncUpdates
        set(value) {
            lottieDrawable.asyncUpdates = value
        }

    /**
     * Wrapper for LottieDrawable#isApplyingOpacityToLayersEnabled.
     */
    var isApplyingOpacityToLayersEnabled: Boolean
        get() = lottieDrawable.isApplyingOpacityToLayersEnabled
        set(value) {
            lottieDrawable.isApplyingOpacityToLayersEnabled = value
        }

    /**
     * Wrapper for LottieDrawable#maintainOriginalImageBounds.
     */
    var maintainOriginalImageBounds: Boolean
        get() = lottieDrawable.maintainOriginalImageBounds
        set(value) {
            lottieDrawable.maintainOriginalImageBounds = value
        }

    /**
     * Wrapper for LottieDrawable#clipToCompositionBounds.
     */
    var clipToCompositionBounds: Boolean
        get() = lottieDrawable.clipToCompositionBounds
        set(value) {
            lottieDrawable.clipToCompositionBounds = value
        }

    /**
     * Wrapper for LottieDrawable#clipTextToBoundingBox.
     */
    var clipTextToBoundingBox: Boolean
        get() = lottieDrawable.clipTextToBoundingBox
        set(value) {
            lottieDrawable.clipToCompositionBounds = value
        }

    /**
     * Wrapper for LottieDrawable#repeatCount.
     */
    var repeatCount: Int
        get() = lottieDrawable.repeatCount
        set(value) {
            lottieDrawable.repeatCount = value
        }

    /**
     * Wrapper for LottieDrawable#imageAssetsFolder.
     */
    var imageAssetsFolder: String?
        get() = lottieDrawable.imageAssetsFolder
        set(value) {
            lottieDrawable.setImagesAssetsFolder(value)
        }

    /**
     * Wrapper for LottieDrawable#speed.
     */
    var speed: Float
        get() = lottieDrawable.speed
        set(value) {
            lottieDrawable.speed = value
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
     * Setter for animation from a file in the raw directory.
     */
    fun setAnimation(
        @RawRes rawRes: Int,
    ) {
        animationResId = rawRes
        animationName = null
        compositionTask = fromRawRes(rawRes)
    }

    fun setAnimation(assetName: String) {
        animationName = assetName
        animationResId = 0
        compositionTask = fromAssets(assetName)
    }

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

    fun setAnimation(
        stream: InputStream?,
        cacheKey: String? = null,
    ) {
        compositionTask = LottieCompositionFactory.fromJsonInputStream(stream, cacheKey)
    }

    fun setAnimation(
        stream: ZipInputStream?,
        cacheKey: String? = null,
    ) {
        compositionTask = LottieCompositionFactory.fromZipStream(stream, cacheKey)
    }

    fun setAnimationFromJson(
        jsonString: String,
        cacheKey: String? = null,
    ) {
        setAnimation(ByteArrayInputStream(jsonString.toByteArray()), cacheKey)
    }

    /**
     * Wrapper for LottieDrawable#playAnimation().
     */
    fun playAnimation() {
        lottieDrawable.playAnimation()
        invalidateLayout()
    }

    /**
     * Wrapper for LottieDrawable#cancelAnimation().
     */
    fun cancelAnimation() {
        lottieDrawable.cancelAnimation()
    }

    /**
     * Wrapper for LottieDrawable#pauseAnimation().
     */
    fun pauseAnimation() {
        lottieDrawable.pauseAnimation()
    }

    /**
     * Wrapper for LottieDrawable#setImageAssetDelegate.
     */
    fun setImageAssetDelegate(assetDelegate: ImageAssetDelegate) {
        lottieDrawable.setImageAssetDelegate(assetDelegate)
    }

    fun setSafeMode(safeMode: Boolean) {
        lottieDrawable.setSafeMode(safeMode)
    }

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
                        Bitmap.Config.ARGB_8888).apply {
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

    private companion object {
        private val TAG = LottieScreen::class.java.simpleName

        private val DEFAULT_FAILURE_LISTENER =
            LottieListener<Throwable> { throwable: Throwable? ->
                throw IllegalStateException("Unable to parse composition", throwable)
            }
    }
}
