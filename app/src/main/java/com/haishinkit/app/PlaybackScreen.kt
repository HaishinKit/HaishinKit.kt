package com.haishinkit.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haishinkit.compose.HaishinKitView
import com.haishinkit.compose.rememberStreamSessionState
import com.haishinkit.rtc.RTCSession
import com.haishinkit.rtc.RTCStream
import com.haishinkit.srt.SrtStream
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@Suppress("unused")
private const val TAG = "PlaybackScreen"

@Suppress("ktlint:standard:function-naming")
@Composable
fun PlaybackScreen(
    modifier: Modifier = Modifier,
    viewModel: PlaybackViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
) {
    val activity = LocalContext.current.mainActivity()
    val pictureInPicture = activity?.pictureInPicture == true
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, activity) {
        val observer =
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    if (activity?.isChangingConfigurations != true) viewModel.stopPlayback()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var errorMessage by remember(viewModel.session) { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshSession().onFailure { errorMessage = it.message ?: "Check the stream URL." } }
    val currentSession = viewModel.session
    if (currentSession == null) {
        Box(modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            StudioSection(stringResource(R.string.studio_playback), viewModel.sessionError ?: stringResource(R.string.studio_preparing)) {
                androidx.compose.material3.TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.studio_settings)) }
            }
        }

        return
    }
    LaunchedEffect(currentSession) {
        (currentSession as? RTCSession)?.failure?.filterNotNull()?.collect { cause ->
            errorMessage = cause.message ?: "WHEP connection failed."
        }
    }
    val session = rememberStreamSessionState(currentSession)
    val readyState by session.readyState.collectAsState()
    PictureInPictureEffect(enabled = readyState == StreamSession.ReadyState.OPEN)
    val toggleConnection: () -> Unit = {
        errorMessage = null
        scope.launch {
            if (session.readyState.value != StreamSession.ReadyState.CLOSED) {
                session.close().onFailure { errorMessage = it.message ?: "Could not close the stream." }
            } else {
                viewModel.sessionError?.let {
                    errorMessage = it
                    return@launch
                }
                session.connect().onFailure {
                    errorMessage = it.message ?: "Check the stream URL and your network connection."
                }
            }
        }
    }

    StudioMediaLayout(
        modifier = modifier,
        pictureInPicture = pictureInPicture,
        preview = {
            key(session.stream) {
                HaishinKitView(
                    stream = session.stream,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        controls = { expanded ->
            PlaybackControls(
                expanded = expanded,
                readyState = readyState,
                errorMessage = errorMessage,
                onOpenSettings = onOpenSettings,
                onToggleConnection = toggleConnection,
                canCancelConnection = session.stream is SrtStream || session.stream is RTCStream,
            )
        },
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun PlaybackControls(
    expanded: Boolean,
    readyState: StreamSession.ReadyState,
    errorMessage: String?,
    onOpenSettings: () -> Unit,
    onToggleConnection: () -> Unit,
    canCancelConnection: Boolean = false,
) {
    Column(
        Modifier.fillMaxSize()
            .background(if (expanded) MaterialTheme.colorScheme.surface else Color.Transparent)
            .safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header and feedback share the scroll area so short windows retain readable content.
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (expanded) {
                        Text(stringResource(R.string.studio_playback), style = MaterialTheme.typography.headlineSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StreamStatus(readyState, idleLabel = R.string.studio_not_connected)
                            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.studio_settings)) }
                        }
                    } else {
                        StudioPreviewHeader(stringResource(R.string.studio_playback), readyState, onOpenSettings, idleLabel = R.string.studio_not_connected)
                    }
                }
            }
            if (errorMessage != null || readyState == StreamSession.ReadyState.CLOSED) {
                Box(Modifier.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
                    StudioSection(
                        stringResource(if (errorMessage != null) R.string.studio_playback_error else R.string.studio_playback_ready),
                        errorMessage ?: stringResource(R.string.studio_playback_help),
                    ) {
                        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.studio_settings)) }
                    }
                }
            }
        }
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            StreamControlButton(
                readyState = readyState,
                startLabel = stringResource(if (errorMessage != null) R.string.studio_retry else R.string.studio_play),
                onClick = onToggleConnection,
                canCancelConnection = canCancelConnection,
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
            )
        }
    }
}
