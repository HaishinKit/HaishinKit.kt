package com.haishinkit.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.pager.HorizontalPagerIndicator
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.haishinkit.compose.HaishinKitView
import com.haishinkit.compose.rememberRecorderState
import com.haishinkit.compose.rememberStreamSessionState
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.graphics.effect.MonochromeVideoEffect
import com.haishinkit.graphics.effect.MosaicVideoEffect
import com.haishinkit.graphics.effect.SepiaVideoEffect
import com.haishinkit.lottie.LottieScreen
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.source.AudioRecordSource
import com.haishinkit.media.source.Camera2Source
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.TextScreenObject
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

private const val TAG = "CameraScreen"

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val videoEffectItems = mutableListOf<VideoEffectItem>()
    videoEffectItems.add(VideoEffectItem("Normal", null))
    videoEffectItems.add(VideoEffectItem("Monochrome", MonochromeVideoEffect()))
    videoEffectItems.add(VideoEffectItem("Mosaic", MosaicVideoEffect()))
    videoEffectItems.add(VideoEffectItem("Sepia", SepiaVideoEffect()))
    // videoEffectItems.add(VideoEffectItem("Monochrome2", Monochrome2VideoEffect()))

    // HaishinKit
    val mixer =
        remember { MediaMixer(context) }

    val session =
        rememberStreamSessionState(
            StreamSession
                .Builder(context, Preference.shared.rtmpURL.toUri())
                .build(),
        )

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        lifecycleOwner.lifecycle.addObserver(mixer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(mixer)
            mixer.dispose()
            // session.dispose()
        }
    }

    val configuration = LocalConfiguration.current
    when (configuration.orientation) {
        Configuration.ORIENTATION_PORTRAIT -> {
            mixer.screen.frame =
                Rect(
                    0,
                    0,
                    Screen.DEFAULT_HEIGHT,
                    Screen.DEFAULT_WIDTH,
                )
        }

        Configuration.ORIENTATION_LANDSCAPE -> {
            mixer.screen.frame =
                Rect(
                    0,
                    0,
                    Screen.DEFAULT_WIDTH,
                    Screen.DEFAULT_HEIGHT,
                )
        }

        else -> {
        }
    }

    val pagerState =
        rememberPagerState(pageCount = {
            videoEffectItems.size
        })

    LaunchedEffect(pagerState, 0) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val item = videoEffectItems[page]
            mixer.setVideoEffect(0, item.videoEffect ?: DefaultVideoEffect.shared)
        }
    }

    LaunchedEffect(Unit) {
        mixer.registerOutput(session.stream)
    }

    Box(modifier = modifier) {
        HaishinKitView(
            stream = session.stream,
            videoGravity = VideoGravity.RESIZE_ASPECT_FILL,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(8.dp)
                    .alpha(0.8F),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CameraDeviceControllerView(onAudioPermissionStatus = { state ->
                when (state.status) {
                    PermissionStatus.Granted -> {
                        scope.launch {
                            mixer.attachAudio(0, AudioRecordSource(context))
                        }
                    }

                    is PermissionStatus.Denied -> {
                        scope.launch {
                            mixer.attachAudio(0, null)
                        }
                    }
                }
            }, onVideoPermissionStatus = { state ->
                when (state.status) {
                    PermissionStatus.Granted -> {
                        scope.launch {
                            mixer.attachVideo(0, Camera2Source(context))
                        }
                    }

                    is PermissionStatus.Denied -> {
                    }
                }
            })
            Spacer(modifier = Modifier.weight(1f))

            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(end = 0.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                val item = videoEffectItems[page]
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.name,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .align(alignment = Alignment.Center)
                                .background(
                                    color = Color.Black,
                                    shape = RoundedCornerShape(20.dp),
                                ).padding(8.dp, 0.dp),
                    )
                }
            }

            HorizontalPagerIndicator(
                pagerState = pagerState,
                pageCount = videoEffectItems.size,
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(32.dp),
            )

            val recorderState = rememberRecorderState(context, session.stream)

            CameraControllerView(
                isRecording = recorderState.isRecording,
                isConnected = session.isConnected,
                onClickScreenShot = {
                    mixer.screen.readPixels {
                        val bitmap = it ?: return@readPixels
                        val bytes = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bytes)
                        val path =
                            MediaStore.Images.Media.insertImage(
                                context.contentResolver,
                                bitmap,
                                "Title",
                                null,
                            )
                        val imageUri = path.toUri()
                        val share = Intent(Intent.ACTION_SEND)
                        share.type = "image/jpeg"
                        share.putExtra(Intent.EXTRA_STREAM, imageUri)
                        context.startActivity(Intent.createChooser(share, "Select"))
                    }
                },
                onClickConnect = {
                    scope.launch {
                        if (session.isConnected) {
                            session.close()
                        } else {
                            session.connect().onFailure {
                                Toast
                                    .makeText(context, it.message, Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    }
                },
                onClickRecording = {
                    if (recorderState.isRecording) {
                        recorderState.stopRecording()
                    } else {
                        recorderState.startRecording(
                            File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                "output.mp4",
                            ).toString(),
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                        )
                    }
                },
            )
        }
    }

    LaunchedEffect(Unit) {
        val text = TextScreenObject()
        text.size = 60f
        text.value = "Hello World!!"
        text.layoutMargins.set(0, 0, 16, 16)
        text.horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_RIGHT
        text.verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_BOTTOM

        val image = ImageScreenObject()
        image.bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.game_jikkyou)
        image.verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_BOTTOM
        image.frame.set(0, 0, 180, 180)

        mixer.screen.addChild(image)
        mixer.screen.addChild(text)

        val lottie = LottieScreen(context)
        lottie.setAnimation(R.raw.a1707149669115)
        lottie.frame.set(0, 0, 200, 200)
        lottie.horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_RIGHT
        lottie.playAnimation()
        mixer.screen.addChild(lottie)
    }
}
