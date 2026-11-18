package com.haishinkit.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

internal fun Context.mainActivity(): MainActivity? =
    when (this) {
        is MainActivity -> this
        is ContextWrapper -> baseContext.mainActivity()
        else -> null
    }

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun PictureInPictureEffect(enabled: Boolean) {
    val activity = LocalContext.current.mainActivity()
    DisposableEffect(activity, enabled) {
        activity?.updatePictureInPicture(enabled)
        onDispose { activity?.updatePictureInPicture(false) }
    }
}
