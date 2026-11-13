package com.haishinkit.screen

import android.graphics.PointF
import android.graphics.RectF
import android.util.Size
import com.haishinkit.graphics.VideoGravity
import kotlin.math.max
import kotlin.math.min

/**
 * Converts between composition pixels and preview-local pixels using centered [videoGravity].
 *
 * Use the preview's actual pixel size, not dp. Create a new transform when either size or the
 * gravity changes. Points outside the displayed content are not clamped; [toScreen] can return
 * negative coordinates in letterbox bars. Invalid sizes or non-finite input return `null`.
 */
class ScreenCoordinateTransform(
    val screenSize: Size,
    val previewSize: Size,
    val videoGravity: VideoGravity = VideoGravity.RESIZE_ASPECT,
) {
    private val valid = screenSize.width > 0 && screenSize.height > 0 && previewSize.width > 0 && previewSize.height > 0
    private val widthScale = if (valid) previewSize.width.toFloat() / screenSize.width else 0f
    private val heightScale = if (valid) previewSize.height.toFloat() / screenSize.height else 0f
    private val scaleX =
        when (videoGravity) {
            VideoGravity.RESIZE -> widthScale
            VideoGravity.RESIZE_ASPECT -> min(widthScale, heightScale)
            VideoGravity.RESIZE_ASPECT_FILL -> max(widthScale, heightScale)
        }
    private val scaleY = if (videoGravity == VideoGravity.RESIZE) heightScale else scaleX
    private val offsetX = (previewSize.width - screenSize.width * scaleX) / 2f
    private val offsetY = (previewSize.height - screenSize.height * scaleY) / 2f

    /** Maps a preview-local point to composition coordinates without clamping. */
    fun toScreen(point: PointF): PointF? {
        if (!valid || !point.x.isFinite() || !point.y.isFinite()) return null
        return PointF((point.x - offsetX) / scaleX, (point.y - offsetY) / scaleY)
            .takeIf { it.x.isFinite() && it.y.isFinite() }
    }

    /** Maps a composition point to preview-local coordinates without clipping. */
    fun toPreview(point: PointF): PointF? {
        if (!valid || !point.x.isFinite() || !point.y.isFinite()) return null
        return PointF(point.x * scaleX + offsetX, point.y * scaleY + offsetY)
            .takeIf { it.x.isFinite() && it.y.isFinite() }
    }

    /** Maps a composition rectangle to preview-local coordinates without clipping. */
    fun toPreview(bounds: RectF): RectF? {
        val origin = toPreview(PointF(bounds.left, bounds.top)) ?: return null
        val end = toPreview(PointF(bounds.right, bounds.bottom)) ?: return null
        return RectF(origin.x, origin.y, end.x, end.y)
    }
}
