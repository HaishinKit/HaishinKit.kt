package com.haishinkit.app.chatgpt

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haishinkit.app.CameraViewModel
import com.haishinkit.app.R

/** Remains over Publish so the composition is available without starting another camera owner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiChatSheet(
    camera: CameraViewModel,
    onDismiss: () -> Unit,
    model: AiChatViewModel = viewModel(),
) {
    val state by model.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(owner, camera, model) {
        val stopping: () -> Unit = { model.pause() }
        camera.onCompositionStopping = stopping
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> model.visible(camera.compositionScreen)
                    Lifecycle.Event.ON_STOP -> model.pause()
                    else -> Unit
                }
            }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.visible(camera.compositionScreen)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (camera.onCompositionStopping === stopping) camera.onCompositionStopping = null
            model.pause()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        AiChatContent(
            state = state,
            onDismiss = onDismiss,
            onConnect = { account -> model.connect(account) { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            onSelectAccount = model::selectAccount,
            onSelectModel = model::selectModel,
            onSignOut = model::signOut,
            onClear = model::clearConversation,
            onSend = model::send,
            onStop = model::stop,
            onUsage = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) },
        )
    }
    if (state.welcome) {
        AlertDialog(
            onDismissRequest = model::acknowledgePlanNotice,
            title = { Text(stringResource(R.string.ai_plan_title)) },
            text = { Text(stringResource(R.string.ai_plan_notice)) },
            confirmButton = { TextButton(onClick = model::acknowledgePlanNotice) { Text(stringResource(R.string.ai_got_it)) } },
        )
    }
    if (state.approval) {
        AlertDialog(
            onDismissRequest = { model.approveImage(false) },
            title = { Text(stringResource(R.string.ai_image_title)) },
            text = { Text(stringResource(R.string.ai_image_consent)) },
            confirmButton = { TextButton(onClick = { model.approveImage(true) }) { Text(stringResource(R.string.ai_allow_image)) } },
            dismissButton = { TextButton(onClick = { model.approveImage(false) }) { Text(stringResource(R.string.ai_decline_image)) } },
        )
    }
}

@Composable
internal fun AiChatContent(
    state: AiChatState,
    onDismiss: () -> Unit,
    onConnect: (String?) -> Unit,
    onSelectAccount: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onSignOut: () -> Unit,
    onClear: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onUsage: () -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    var accountsExpanded by remember { mutableStateOf(false) }
    var modelsExpanded by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val account = state.accounts.firstOrNull { it.id == state.account }
    LaunchedEffect(state.lines.size, state.partial, state.image, state.notice) {
        val count = 1 + state.lines.size + (if (state.partial.isNotBlank()) 1 else 0) + (if (state.image != null) 1 else 0) + (if (state.notice != null) 1 else 0)
        list.scrollToItem(count - 1)
    }

    Column(
        Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ai_close)) }
        }
        LazyColumn(Modifier.weight(1f, fill = false), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ai_intro), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(if (state.mcpReady) R.string.ai_camera_ready else R.string.ai_camera_unavailable), style = MaterialTheme.typography.labelLarge)
                    if (account != null) {
                        Column {
                            TextButton(onClick = { accountsExpanded = true }, enabled = !state.busy) { Text(account.label) }
                            DropdownMenu(expanded = accountsExpanded, onDismissRequest = { accountsExpanded = false }) {
                                state.accounts.forEach { saved ->
                                    DropdownMenuItem(text = { Text(saved.label) }, onClick = {
                                        accountsExpanded = false
                                        onSelectAccount(saved.id)
                                    })
                                }
                            }
                        }
                    }
                    if (account?.planEnabled != true) {
                        Button(onClick = { onConnect(state.account) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                            Text("Continue with ChatGPT")
                        }
                        Text(stringResource(R.string.ai_plan_required), style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (account?.planEnabled == true) {
                            TextButton(onClick = { onConnect(state.account) }, enabled = !state.busy) { Text(stringResource(R.string.ai_reconnect)) }
                        }
                        if (account?.connected == true) {
                            TextButton(onClick = onSignOut, enabled = !state.busy) { Text(stringResource(R.string.ai_sign_out)) }
                        }
                        TextButton(onClick = { onConnect(null) }, enabled = !state.busy) { Text(stringResource(R.string.ai_add_account)) }
                        TextButton(onClick = onUsage) { Text(stringResource(R.string.ai_manage_usage)) }
                        if (state.lines.isNotEmpty()) TextButton(onClick = onClear, enabled = !state.busy) { Text(stringResource(R.string.ai_clear)) }
                    }
                    if (account?.planEnabled == true) Text(stringResource(R.string.ai_using_plan), style = MaterialTheme.typography.labelMedium)
                    if (state.models.isNotEmpty()) {
                        Column {
                            TextButton(onClick = { modelsExpanded = true }, enabled = !state.busy && state.lines.isEmpty()) {
                                Text(stringResource(R.string.ai_model, state.models.firstOrNull { it.slug == state.model }?.label.orEmpty()))
                            }
                            DropdownMenu(expanded = modelsExpanded, onDismissRequest = { modelsExpanded = false }) {
                                state.models.forEach { available ->
                                    DropdownMenuItem(text = { Text(available.label) }, onClick = {
                                        modelsExpanded = false
                                        onSelectModel(available.slug)
                                    })
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            items(state.lines) { line ->
                Surface(color = if (line.user) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(if (line.user) R.string.ai_you else R.string.ai_assistant), style = MaterialTheme.typography.labelMedium)
                        Text(line.text)
                    }
                }
            }
            if (state.partial.isNotBlank()) item { Text(state.partial, Modifier.padding(12.dp)) }
            if (state.image != null) item { Text(stringResource(R.string.ai_image_sent), style = MaterialTheme.typography.labelMedium) }
            state.notice?.let { notice ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(notice, color = MaterialTheme.colorScheme.error)
                        if (state.usageLimited) Button(onClick = onUsage) { Text(stringResource(R.string.ai_manage_usage)) }
                    }
                }
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { if (it.length <= 8_000) prompt = it },
                label = { Text(stringResource(R.string.ai_prompt)) },
                enabled = !state.busy,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            if (state.busy) {
                TextButton(onClick = onStop) { Text(stringResource(R.string.ai_stop)) }
            } else {
                Button(onClick = {
                    onSend(prompt.trim())
                    prompt = ""
                }, enabled = account?.planEnabled == true && state.mcpReady && !state.welcome && state.model != null && prompt.isNotBlank()) {
                    Text(stringResource(R.string.ai_send))
                }
            }
        }
    }
}
