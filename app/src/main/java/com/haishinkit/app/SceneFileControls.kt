package com.haishinkit.app

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal data class SceneFileActions(val pickerOpen: Boolean, val save: () -> Unit, val open: () -> Unit)

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun rememberSceneFileActions(
    onSave: suspend (Uri) -> Result<Unit>,
    onOpen: suspend (Uri) -> Result<Unit>,
    onError: (String) -> Unit,
    onBusyChange: (Boolean) -> Unit = {},
): SceneFileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickerOpen by remember { mutableStateOf(false) }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            pickerOpen = false
            if (uri != null) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    onBusyChange(true)
                    try {
                        onSave(uri).onSuccess { Toast.makeText(context, R.string.scene_saved, Toast.LENGTH_SHORT).show() }
                            .onFailure { onError(context.getString(R.string.scene_file_error)) }
                    } finally {
                        onBusyChange(false)
                    }
                }
            }
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pickerOpen = false
            if (uri != null) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    onBusyChange(true)
                    try {
                        onOpen(uri).onSuccess { Toast.makeText(context, R.string.scene_restored, Toast.LENGTH_SHORT).show() }
                            .onFailure { onError(context.getString(R.string.scene_file_error)) }
                    } finally {
                        onBusyChange(false)
                    }
                }
            }
        }
    return SceneFileActions(
        pickerOpen,
        save = {
            pickerOpen = true
            save.launch("HaishinKit.scene")
        },
        open = {
            pickerOpen = true
            open.launch(arrayOf("*/*"))
        },
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SceneFileControls(
    enabled: Boolean,
    busy: Boolean,
    actions: SceneFileActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.scene_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (enabled) R.string.scene_file_help else R.string.scene_stop_first), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.testTag("scene-save"), enabled = enabled && !busy && !actions.pickerOpen, onClick = actions.save) { Text(stringResource(R.string.scene_save)) }
            OutlinedButton(modifier = Modifier.testTag("scene-open"), enabled = enabled && !busy && !actions.pickerOpen, onClick = actions.open) { Text(stringResource(R.string.scene_open)) }
        }
        if (busy) Text(stringResource(R.string.scene_file_working), style = MaterialTheme.typography.bodySmall)
    }
}
