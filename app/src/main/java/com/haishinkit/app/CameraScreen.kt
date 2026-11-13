package com.haishinkit.app

import android.media.MediaMuxer
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.pager.HorizontalPagerIndicator
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.haishinkit.compose.HaishinKitView
import com.haishinkit.compose.rememberMediaRecorderState
import com.haishinkit.compose.rememberStreamSessionState
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.rtc.RTCSession
import com.haishinkit.rtc.RTCStream
import com.haishinkit.screen.TextScreenObject
import com.haishinkit.srt.SrtStream
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "CameraScreen"

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingText by remember { mutableStateOf<TextScreenObject?>(null) }
    var insertionPosition by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(Unit) {
        viewModel.refreshSession().onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
    }

    val cameraList by viewModel.cameraList.collectAsState()
    val selectedCamera by viewModel.selectedCamera.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(viewModel)
        viewModel.startRunning()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(viewModel)
            viewModel.stopRunning()
        }
    }

    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        viewModel.onConfigurationChanged(configuration)
    }

    val pagerState =
        rememberPagerState(pageCount = {
            viewModel.videoEffectItems.size
        })

    val currentSession = viewModel.session
    if (currentSession == null) {
        Text(viewModel.sessionError ?: "Preparing stream...", modifier = modifier.safeDrawingPadding().padding(16.dp))
        return
    }
    LaunchedEffect(currentSession) {
        (currentSession as? RTCSession)?.failure?.filterNotNull()?.collect { cause ->
            Toast.makeText(context, cause.message ?: "WHIP connection failed.", Toast.LENGTH_LONG).show()
        }
    }
    val sessionState = rememberStreamSessionState(currentSession)
    val sessionReadyState by sessionState.readyState.collectAsState()
    val sessionActive = sessionReadyState != StreamSession.ReadyState.CLOSED

    LaunchedEffect(pagerState, 0) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val item = viewModel.videoEffectItems[page]
            viewModel.setVideoEffect(item.videoEffect ?: DefaultVideoEffect.shared)
        }
    }

    insertionPosition?.let { position ->
        ScreenObjectAddDialog(
            onAdd = { kind ->
                viewModel.addScreenObject(kind, position)
                insertionPosition = null
            },
            onDismiss = { insertionPosition = null },
        )
    }

    editingText?.let { target ->
        key(target.id) {
            ScreenObjectTextEditor(
                initialValue = target.value,
                onConfirm = { value ->
                    viewModel.screenObjectDragController.updateText(target, value)
                    editingText = null
                },
                onDismiss = { editingText = null },
            )
        }
    }

    Box(modifier) {
        Box(
            modifier =
                Modifier.fillMaxSize().pointerInput(viewModel, configuration.orientation) {
                    val controller = viewModel.screenObjectDragController
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val hitObject = controller.begin(down.position, size)
                        down.consume()
                        var isTap = true
                        var isTransforming = false
                        try {
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.isConsumed }) break
                                if (event.changes.size > 1 ||
                                    event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop } ||
                                    event.changes.any { it.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis }
                                ) {
                                    isTap = false
                                }
                                if (!isTap && hitObject) {
                                    val previous = if (!isTransforming && event.changes.size == 1) down.position else event.calculateCentroid(useCurrent = false)
                                    controller.transform(previous, event.calculateCentroid(), event.calculateZoom(), size)
                                    isTransforming = true
                                }
                                if (isTap && event.changes.none { it.pressed }) {
                                    if (hitObject) {
                                        editingText = controller.textForEditing()
                                    } else {
                                        controller.clearFocus()
                                        insertionPosition = controller.screenPosition(down.position, size)
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            controller.end()
                        }
                    }
                }
                    .pointerInput(viewModel, configuration.orientation) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Scroll) continue
                                val change = event.changes.firstOrNull() ?: continue
                                if (!change.isConsumed && viewModel.screenObjectDragController.scroll(change.position, change.scrollDelta.y, size)) {
                                    change.consume()
                                }
                            }
                        }
                    },
        ) {
            key(currentSession.stream) {
                HaishinKitView(
                    stream = currentSession.stream,
                    videoGravity = VideoGravity.RESIZE_ASPECT_FILL,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        ScreenObjectSelectionOverlay(
            controller = viewModel.screenObjectDragController,
            onDelete = { viewModel.removeFocusedScreenObject() },
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
                },
                onVideoPermissionStatus = { state ->
                    when (state.status) {
                        PermissionStatus.Granted -> {
                            viewModel.selectCameraDevice(selectedCamera)
                        }

                        is PermissionStatus.Denied -> {
                        }
                    }
                },
            )
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
                                )
                                .padding(8.dp, 0.dp),
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

            val recorderState = rememberMediaRecorderState(viewModel.recorder)

            CameraControllerView(
                isRecording = recorderState.isRecording,
                isConnected = sessionActive,
                readyState = sessionReadyState,
                canCancelConnection = sessionState.stream is SrtStream || sessionState.stream is RTCStream,
                onClickScreenShot = {
                    viewModel.takeSnapShot(context)
                },
                onClickConnect = {
                    scope.launch {
                        if (sessionActive) {
                            sessionState.close().onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        } else {
                            viewModel.sessionError?.let {
                                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            sessionState.connect().onFailure {
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
