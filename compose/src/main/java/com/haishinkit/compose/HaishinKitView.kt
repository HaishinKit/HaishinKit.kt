@file:Suppress("MemberVisibilityCanBePrivate")

package com.haishinkit.compose

import android.graphics.Color
import android.view.TextureView
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
 * Displays a stream using an Android video view hosted in Compose.
 *
 * The view is registered as a stream output when created and unregistered when disposed.
 * Keep [stream] and [viewType] stable for this composable's lifetime; use a Compose `key` to
 * recreate the view when changing them or [videoGravity].
 *
 * @param stream The stream to display.
 * @param modifier Layout and appearance modifiers for the hosted view.
 * @param backgroundColor The Android ARGB background color; defaults to black.
 * @param isOpaque Whether the texture view is opaque. Applies only to [HaishinKitViewType.TextureView].
 * @param videoGravity The initial scaling mode for the video.
 * @param viewType The Android view implementation to create.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun HaishinKitView(
    stream: Stream,
    modifier: Modifier = Modifier,
    backgroundColor: Int = Color.BLACK,
    isOpaque: Boolean = true,
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
            stream.unregisterOutput(videoView)
        }
    }

    AndroidView(
        factory = {
            videoView.apply {
                this.videoGravity = videoGravity
                this.setBackgroundColor(backgroundColor)
                (this as? TextureView)?.let {
                    it.isOpaque = isOpaque
                }
                stream.registerOutput(this)
            }
        },
        update = {
            (videoView as? TextureView)?.let {
                it.isOpaque = isOpaque
            }
            videoView.setBackgroundColor(backgroundColor)
        },
        modifier = modifier,
    )
}
