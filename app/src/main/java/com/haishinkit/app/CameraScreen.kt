package com.haishinkit.app

import android.media.MediaMuxer
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.isGranted
import com.haishinkit.compose.HaishinKitView
import com.haishinkit.compose.rememberMediaRecorderState
import com.haishinkit.compose.rememberStreamSessionState
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.rtc.RtcSession
import com.haishinkit.rtc.RtcStream
import com.haishinkit.screen.TextScreenObject
import com.haishinkit.srt.SrtStream
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "CameraScreen"

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
) {
    var showIngest by remember { mutableStateOf(false) }
    var applyingIngest by remember { mutableStateOf(false) }
    var selectedIngest by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val pictureInPicture = context.mainActivity()?.pictureInPicture == true
    val scope = rememberCoroutineScope()
    var editingText by remember { mutableStateOf<TextScreenObject?>(null) }
    var insertionPosition by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(Unit) {
        viewModel.refreshSession().onFailure { errorMessage = it.message ?: context.getString(R.string.studio_connection_error) }
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
    LaunchedEffect(configuration.orientation, pictureInPicture) {
        if (!pictureInPicture) viewModel.onConfigurationChanged(configuration)
    }

    var selectedEffect by remember { mutableIntStateOf(0) }
    var filterFeedbackVisible by remember { mutableStateOf(false) }
    var showFilterHint by remember { mutableStateOf(true) }

    val permissions =
        rememberCameraPermissions(
            onAudioPermissionStatus = { state ->
                if (state.status == PermissionStatus.Granted) viewModel.selectAudioDevice()
            },
            onVideoPermissionStatus = { state ->
                if (state.status == PermissionStatus.Granted) viewModel.selectCameraDevice(viewModel.selectedCamera.value, retry = false)
            },
        )

    if (showIngest && !pictureInPicture) {
        IngestDestinationDialog(
            discovery = viewModel.ingestDiscovery,
            onDismiss = { showIngest = false },
            onSelect = { endpoint ->
                showIngest = false
                applyingIngest = true
                scope.launch {
                    try {
                        viewModel.selectIngest(endpoint).onSuccess { selectedIngest = endpoint.name }.onFailure { errorMessage = it.message ?: context.getString(R.string.studio_connection_error) }
                    } finally {
                        applyingIngest = false
                    }
                }
            },
        )
    }

    val currentSession = viewModel.session
    if (currentSession == null) {
        Box(modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            StudioSection(stringResource(R.string.studio_publish), viewModel.sessionError ?: stringResource(R.string.studio_preparing)) {
                TextButton(onClick = { showIngest = true }, enabled = !applyingIngest) { Text(stringResource(R.string.ingest_action)) }
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.studio_settings)) }
            }
        }

        return
    }
    LaunchedEffect(currentSession) {
        (currentSession as? RtcSession)?.failure?.filterNotNull()?.collect { cause ->
            errorMessage = cause.message ?: context.getString(R.string.studio_connection_error)
        }
    }
    val sessionState = rememberStreamSessionState(currentSession)
    val sessionReadyState by sessionState.readyState.collectAsState()
    PictureInPictureEffect(enabled = sessionReadyState == StreamSession.ReadyState.OPEN)
    val recorderState = rememberMediaRecorderState(viewModel.recorder)
    val sessionActive = sessionReadyState != StreamSession.ReadyState.CLOSED

    LaunchedEffect(selectedEffect) {
        val item = viewModel.videoEffectItems[selectedEffect]
        viewModel.setVideoEffect(item.videoEffect ?: DefaultVideoEffect.shared)
        filterFeedbackVisible = true
        delay(if (showFilterHint) 2500L else 900L)
        filterFeedbackVisible = false
        showFilterHint = false
    }

    if (!pictureInPicture) {
        insertionPosition?.let { position ->
            ScreenObjectAddDialog(
                onAdd = { kind ->
                    viewModel.addScreenObject(kind, position) { message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
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
    }

    StudioMediaLayout(
        modifier = modifier,
        pictureInPicture = pictureInPicture,
        preview = { expanded ->
            StudioPreviewFrame(expanded || viewModel.loadedSceneSize != null, viewModel.compositionSize.width.toFloat() / viewModel.compositionSize.height) {
                Box(
                    modifier =
                        Modifier.fillMaxSize().pointerInput(viewModel, configuration.orientation, viewModel.sceneFileBusy) {
                            if (viewModel.sceneFileBusy) return@pointerInput
                            val controller = viewModel.screenObjectDragController
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val hitObject = controller.begin(down.position, size)
                                down.consume()
                                var isTap = true
                                var isTransforming = false
                                var endPosition = down.position
                                var multiplePointers = false
                                var cancelled = false
                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                        if (event.changes.any { it.isConsumed }) {
                                            cancelled = true
                                            break
                                        }
                                        if (event.changes.size > 1) multiplePointers = true
                                        event.changes.firstOrNull { it.id == down.id }?.let { endPosition = it.position }
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
                                if (!cancelled && !isTap) {
                                    val step = previewFilterStep(down.position, endPosition, 48.dp.toPx(), hitObject, multiplePointers)
                                    val count = viewModel.videoEffectItems.size
                                    if (step != 0 && count > 1) selectedEffect = (selectedEffect + step + count) % count
                                }
                            }
                        }
                            .pointerInput(viewModel, configuration.orientation, viewModel.sceneFileBusy) {
                                if (viewModel.sceneFileBusy) return@pointerInput
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

                if (!pictureInPicture) {
                    ScreenObjectSelectionOverlay(
                        controller = viewModel.screenObjectDragController,
                        onDelete = { if (!viewModel.sceneFileBusy) viewModel.removeFocusedScreenObject() },
                    )

                    if (filterFeedbackVisible) {
                        Surface(modifier = Modifier.align(Alignment.Center), color = PreviewColors.container) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("‹ ${viewModel.videoEffectItems[selectedEffect].name} ›", color = PreviewColors.content)
                                if (showFilterHint) Text(stringResource(R.string.studio_filter_swipe), color = PreviewColors.content)
                            }
                        }
                    }
                }
            }
        },
        controls = { expanded ->
            PublishControls(
                expanded = expanded,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                readyState = sessionReadyState,
                needsPermissions = !permissions.audio.status.isGranted || !permissions.camera.status.isGranted,
                errorMessage = errorMessage,
                onDismissError = { errorMessage = null },
                onOpenSettings = onOpenSettings,
                onOpenIngest =
                    if (applyingIngest) {
                        null
                    } else {
                        { showIngest = true }
                    },
                settings = {
                    SceneFileControls(
                        enabled = !sessionActive && !recorderState.isRecording,
                        busy = viewModel.sceneFileBusy,
                        onSave = viewModel::saveSceneFile,
                        onOpen = { uri ->
                            editingText = null
                            insertionPosition = null
                            viewModel.restoreSceneFile(uri)
                        },
                        onError = { errorMessage = it },
                    )
                    selectedIngest?.let { Text(stringResource(R.string.ingest_selected, it)) }
                    PublishEncodingControls(
                        settings = viewModel.encoding,
                        protocol = viewModel.publishingProtocol,
                        publishing = sessionActive,
                        onUpdate = { settings ->
                            viewModel.updateEncoding(settings).also { result ->
                                if (result.isSuccess) errorMessage = null
                            }
                        },
                    )
                    CameraDeviceControllerView(
                        camera = selectedCamera,
                        cameras = cameraList,
                        onCameraSelected = { camera ->
                            viewModel.selectCameraDevice(camera)
                        },
                        permissions = permissions,
                    )
                    viewModel.mainCameraSource?.let { source ->

                        var torch by remember(source) { mutableStateOf(source.isTorchEnabled) }
                        var zoom by remember(source) { mutableStateOf(source.zoomRatioRange.clamp(source.zoomRatio)) }
                        androidx.compose.material3.Button(
                            enabled = source.isTorchSupported,
                            onClick = {
                                torch = !torch
                                source.isTorchEnabled = torch
                            },
                        ) {
                            Text(stringResource(if (torch) R.string.studio_torch_on else R.string.studio_torch_off))
                        }
                        val zoomLabel = stringResource(R.string.studio_zoom)
                        val zoomDescription = "%.1fx".format(zoom)
                        Text("$zoomLabel: $zoomDescription")
                        val range = source.zoomRatioRange
                        if (range.upper > range.lower) {
                            androidx.compose.material3.Slider(
                                modifier =
                                    Modifier.semantics {
                                        contentDescription = zoomLabel
                                        stateDescription = zoomDescription
                                    },
                                value = zoom,
                                onValueChange = {
                                    zoom = it
                                    source.zoomRatio = it
                                },
                                valueRange = range.lower..range.upper,
                            )
                        }
                    }

                    Text(stringResource(R.string.studio_effects))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        viewModel.videoEffectItems.forEachIndexed { index, item ->
                            FilterChip(selected = selectedEffect == index, onClick = { selectedEffect = index }, label = { Text(item.name) })
                        }
                    }
                    if (BuildConfig.VQMETRIX_AVAILABLE) {
                        Button(onClick = { viewModel.captureQuality(context) }) {
                            Text(stringResource(R.string.vq_capture_action))
                        }
                    }
                },
                actions = {
                    CameraControllerView(
                        isRecording = recorderState.isRecording,
                        controlsEnabled = !viewModel.sceneFileBusy,
                        isConnected = sessionActive,
                        readyState = sessionReadyState,
                        canCancelConnection = sessionState.stream is SrtStream || sessionState.stream is RtcStream,
                        onClickScreenShot = {
                            viewModel.takeSnapShot(context)
                        },
                        onClickConnect = {
                            if (applyingIngest || viewModel.sceneFileBusy) return@CameraControllerView
                            errorMessage = null
                            scope.launch {
                                if (sessionActive) {
                                    sessionState.close().onFailure { errorMessage = it.message ?: context.getString(R.string.studio_connection_error) }
                                } else {
                                    viewModel.sessionError?.let {
                                        errorMessage = it
                                        return@launch
                                    }
                                    viewModel.preparePublishing().onFailure {
                                        errorMessage = it.message ?: context.getString(R.string.studio_encoding_error)
                                    }.getOrElse { return@launch }
                                    if (viewModel.sceneFileBusy) return@launch
                                    sessionState.connect().onFailure {
                                        errorMessage = it.message ?: context.getString(R.string.studio_connection_error)
                                    }
                                }
                            }
                        },
                        onClickRecording = {
                            if (viewModel.sceneFileBusy) return@CameraControllerView
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
                },
            )
        },
    )
}
