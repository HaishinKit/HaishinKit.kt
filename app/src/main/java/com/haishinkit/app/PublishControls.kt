package com.haishinkit.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.haishinkit.stream.StreamSession

/** Edge controls leave the preview clear; camera adjustments live in a modal sheet. */
@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PublishControls(
    modifier: Modifier = Modifier,
    readyState: StreamSession.ReadyState = StreamSession.ReadyState.CLOSED,
    needsPermissions: Boolean = false,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenIngest: (() -> Unit)? = null,
    settings: @Composable ColumnScope.() -> Unit,
    actions: @Composable () -> Unit,
) {
    var toolsExpanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                StreamStatus(readyState, overlay = true)
                onOpenIngest?.let { open ->
                    Surface(shape = MaterialTheme.shapes.large, color = PreviewColors.container) {
                        TextButton(onClick = open, enabled = readyState == StreamSession.ReadyState.CLOSED) {
                            Text(stringResource(R.string.ingest_action), color = PreviewColors.content)
                        }
                    }
                }

                Surface(shape = MaterialTheme.shapes.large, color = PreviewColors.container) {
                    IconButton(onClick = { toolsExpanded = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.studio_camera_tools), tint = PreviewColors.content)
                    }
                }
            }
            if (needsPermissions) {
                Surface(shape = MaterialTheme.shapes.small, color = PreviewColors.container) {
                    TextButton(onClick = { toolsExpanded = true }) { Text(stringResource(R.string.studio_permission_action), color = PreviewColors.content) }
                }
            }
            errorMessage?.let { StudioError(it, onDismiss = onDismissError) }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, PreviewColors.container)))
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.widthIn(max = 520.dp).fillMaxWidth()) { actions() }
        }
    }
    if (toolsExpanded) {
        ModalBottomSheet(onDismissRequest = { toolsExpanded = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.studio_camera_tools), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                    IconButton(onClick = { toolsExpanded = false }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.studio_close_tools))
                    }
                }
                TextButton(onClick = {
                    toolsExpanded = false
                    onOpenSettings()
                }) { Text(stringResource(R.string.studio_settings)) }
                settings()
            }
        }
    }
}
