package com.haishinkit.app

import android.media.MediaMuxer
import android.os.Environment
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "CameraScreen"

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel(),
    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val cameraList by viewModel.cameraList.collectAsState()
    val selectedCamera by viewModel.selectedCamera.collectAsState()

    val session =
        rememberStreamSessionState(
            StreamSession
                .Builder(context, Preference.shared.rtmpURL.toUri())
                .build(),
        )

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(viewModel)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(viewModel)
        }
    }

    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        viewModel.onConfigurationChanged(configuration)
    }

    LaunchedEffect(Unit) {
        viewModel.registerOutput(session.stream)
    }

    val pagerState =
        rememberPagerState(pageCount = {
            viewModel.videoEffectItems.size
        })

    LaunchedEffect(pagerState, 0) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val item = viewModel.videoEffectItems[page]
            viewModel.setVideoEffect(item.videoEffect ?: DefaultVideoEffect.shared)
        }
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
            CameraDeviceControllerView(
                camera = selectedCamera,
                cameras = cameraList,
                onCameraSelected = { camera ->
                    viewModel.selectCameraDevice(camera)
                },
                onAudioPermissionStatus = { state ->
                when (state.status) {
                    PermissionStatus.Granted -> {
                        viewModel.selectAudioDevice()
                    }

                    is PermissionStatus.Denied -> {
                        // viewModel.selectAudioDevice(null)
                    }
                }
            }, onVideoPermissionStatus = { state ->
                when (state.status) {
                    PermissionStatus.Granted -> {
                        viewModel.selectCameraDevice(selectedCamera)
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
                val item = viewModel.videoEffectItems[page]
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
                pageCount = viewModel.videoEffectItems.size,
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
                    viewModel.takeSnapShot(context)
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
}
