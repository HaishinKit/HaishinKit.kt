package com.haishinkit.app

import android.content.Context
import android.util.Size
import com.haishinkit.media.source.Camera2Source

/** Preserve requested controls when composition changes reopen the same main camera. */
internal fun Camera2Source?.replacement(
    context: Context,
    cameraId: String,
    maximumSize: Size? = null,
): Camera2Source =
    Camera2Source(context, cameraId).also { replacement ->
        replacement.maximumSize = maximumSize
        replacement.copyRequestedControlsFrom(this)
    }

/** Read again after attachment, since opening the replacement suspends. */
internal fun Camera2Source.copyRequestedControlsFrom(previous: Camera2Source?) {
    if (previous?.cameraId == cameraId) {
        isTorchEnabled = previous.isTorchEnabled
        zoomRatio = previous.zoomRatio
    }
}
