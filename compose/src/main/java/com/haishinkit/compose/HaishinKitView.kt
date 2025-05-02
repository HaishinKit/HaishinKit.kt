@file:Suppress("MemberVisibilityCanBePrivate")

package com.haishinkit.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.stream.Stream
import com.haishinkit.view.HkSurfaceView
import com.haishinkit.view.HkTextureView

/**
 * The main view renders a [Stream] object.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun HaishinKitView(
    stream: Stream,
    modifier: Modifier = Modifier,
    videoGravity: VideoGravity = VideoGravity.RESIZE_ASPECT,
    viewType: HaishinKitViewType = HaishinKitViewType.SurfaceView,
) {
    val context = LocalContext.current

    val videoView =
        remember(context) {
            when (viewType) {
                HaishinKitViewType.SurfaceView -> HkSurfaceView(context)
                HaishinKitViewType.TextureView -> HkTextureView(context)
            }
        }

    DisposableEffect(Unit) {
        onDispose {
            stream.attachView(videoView)
        }
    }

    AndroidView(
        factory = {
            videoView.apply {
                this.videoGravity = videoGravity
                stream.attachView(this)
            }
        },
        modifier = modifier,
    )
}
