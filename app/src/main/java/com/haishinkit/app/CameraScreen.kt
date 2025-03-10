package com.haishinkit.app

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.util.Log
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.pager.HorizontalPagerIndicator
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.haishinkit.compose.HaishinKitView
import com.haishinkit.compose.rememberConnectionState
import com.haishinkit.compose.rememberRecorderState
import com.haishinkit.event.Event
import com.haishinkit.event.EventUtils
import com.haishinkit.event.IEventListener
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.lottie.LottieScreen
import com.haishinkit.media.AudioRecordSource
import com.haishinkit.media.Camera2Source
import com.haishinkit.media.MultiCamera2Source
import com.haishinkit.rtmp.RtmpConnection
import com.haishinkit.screen.Image
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.Text
import java.io.File

private const val TAG = "CameraScreen"

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    command: String,
    streamName: String,
    controller: CameraController,
) {
    val context = LocalContext.current

    // HaishinKit
    val connectionState = rememberConnectionState {
        RtmpConnection()
    }

    val stream = remember(connectionState) {
        connectionState.createStream(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            connectionState.dispose()
        }
    }

    val configuration = LocalConfiguration.current
    when (configuration.orientation) {
        Configuration.ORIENTATION_PORTRAIT -> {
            stream.screen.frame = Rect(
                0, 0, Screen.DEFAULT_HEIGHT, Screen.DEFAULT_WIDTH,
            )
        }

        Configuration.ORIENTATION_LANDSCAPE -> {
            stream.screen.frame = Rect(
                0, 0, Screen.DEFAULT_WIDTH, Screen.DEFAULT_HEIGHT,
            )
        }

        else -> {
        }
    }

    val pagerState = rememberPagerState(pageCount = {
        controller.videoEffectItems.size
    })

    LaunchedEffect(pagerState, 0) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val item = controller.videoEffectItems[page]
            stream.videoEffect = item.videoEffect ?: DefaultVideoEffect.shared
        }
    }

    HaishinKitView(
        stream = stream,
        videoGravity = VideoGravity.RESIZE_ASPECT_FILL,
        modifier = Modifier.fillMaxSize(),
    )

    Column(
        modifier = Modifier
            .safeDrawingPadding()
            .fillMaxSize()
            .alpha(0.8F),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CameraDeviceControllerView(onAudioPermissionStatus = { state ->
            when (state.status) {
                PermissionStatus.Granted -> {
                    stream.attachAudio(AudioRecordSource(context))
                }

                is PermissionStatus.Denied -> {
                    stream.attachAudio(null)
                }
            }
        }, onVideoPermissionStatus = { state ->
            when (state.status) {
                PermissionStatus.Granted -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            stream.attachVideo(MultiCamera2Source(context))
                            (stream.videoSource as? MultiCamera2Source)?.apply {
                                try {
                                    open(0, CameraCharacteristics.LENS_FACING_BACK)
                                    open(1, CameraCharacteristics.LENS_FACING_FRONT)
                                    getVideoByChannel(1)?.apply {
                                        frame = Rect(20, 20, 90 + 20, 160 + 20)
                                    }
                                } catch (e: Exception) {
                                    Log.e(
                                        TAG,
                                        "Error while setting up multi-camera: ${e.message}",
                                        e
                                    )
                                    // If multi-camera setup fails, revert to single camera
                                    stream.attachVideo(null)
                                    stream.attachVideo(Camera2Source(context).apply {
                                        try {
                                            open(CameraCharacteristics.LENS_FACING_BACK)
                                        } catch (e: Exception) {
                                            Log.e(
                                                TAG,
                                                "Error while opening main camera: ${e.message}",
                                                e
                                            )
                                        }
                                    })
                                }
                            }
                        } else {
                            stream.attachVideo(Camera2Source(context).apply {
                                try {
                                    open(CameraCharacteristics.LENS_FACING_BACK)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error while opening camera: ${e.message}", e)
                                }
                            })
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "General error while setting up camera: ${e.message}", e)
                    }
                }

                is PermissionStatus.Denied -> {
                    stream.attachVideo(null)
                }
            }
        })
        Spacer(modifier = Modifier.weight(1f))

        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(end = 0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val item = controller.videoEffectItems[page]
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.name,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(alignment = Alignment.Center)
                        .background(
                            color = Color.Black,
                            shape = RoundedCornerShape(20.dp),
                        )
                        .padding(8.dp, 0.dp),
                )
            }
        }

        HorizontalPagerIndicator(
            pagerState = pagerState,
            pageCount = controller.videoEffectItems.size,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(32.dp),
        )

        val recorderState = rememberRecorderState(context, stream)

        CameraControllerView(
            isRecording = recorderState.isRecording,
            isConnected = connectionState.isConnected,
            onClickScreenShot = {
                controller.onScreenShot(stream.screen)
            },
            onClickConnect = {
                if (connectionState.isConnected) {
                    connectionState.close()
                } else {
                    connectionState.connect(command)
                }
            },
            onClickRecording = {
                if (recorderState.isRecording) {
                    recorderState.stopRecording()
                } else {
                    recorderState.startRecording(
                        File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "output.mp4"
                        ).toString(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                    )
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        connectionState.addEventListener(Event.RTMP_STATUS, object : IEventListener {
            override fun handleEvent(event: Event) {
                val data = EventUtils.toMap(event)
                Log.i(TAG, data.toString())
                when (data["code"]) {
                    RtmpConnection.Code.CONNECT_SUCCESS.rawValue -> {
                        stream.publish(streamName)
                    }

                    else -> {

                    }
                }
            }
        })

        val text = Text()
        text.size = 60f
        text.value = "Hello World!!"
        text.layoutMargins.set(0, 0, 16, 16)
        text.horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_RIGHT
        text.verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_BOTTOM

        val image = Image()
        image.bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.game_jikkyou)
        image.verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_BOTTOM
        image.frame.set(0, 0, 180, 180)

        stream.screen.addChild(image)
        stream.screen.addChild(text)

        val lottie = LottieScreen(context)
        lottie.setAnimation(R.raw.a1707149669115)
        lottie.frame.set(0, 0, 200, 200)
        lottie.horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_RIGHT
        lottie.playAnimation()
        stream.screen.addChild(lottie)
    }
}
