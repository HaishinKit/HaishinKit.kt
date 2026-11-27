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
import kotlinx.coroutines.launch

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SceneFileControls(
    enabled: Boolean,
    busy: Boolean,
    onSave: suspend (Uri) -> Result<Unit>,
    onOpen: suspend (Uri) -> Result<Unit>,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickerOpen by remember { mutableStateOf(false) }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            pickerOpen = false
            if (uri != null) {
                scope.launch {
                    onSave(uri).onSuccess { Toast.makeText(context, R.string.scene_saved, Toast.LENGTH_SHORT).show() }
                        .onFailure { onError(it.message ?: context.getString(R.string.scene_file_error)) }
                }
            }
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pickerOpen = false
            if (uri != null) {
                scope.launch {
                    onOpen(uri).onSuccess { Toast.makeText(context, R.string.scene_restored, Toast.LENGTH_SHORT).show() }
                        .onFailure { onError(it.message ?: context.getString(R.string.scene_file_error)) }
                }
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.scene_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (enabled) R.string.scene_file_help else R.string.scene_stop_first), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.testTag("scene-save"), enabled = enabled && !busy && !pickerOpen, onClick = {
                pickerOpen = true
                save.launch("HaishinKit.scene")
            }) { Text(stringResource(R.string.scene_save)) }
            OutlinedButton(modifier = Modifier.testTag("scene-open"), enabled = enabled && !busy && !pickerOpen, onClick = {
                pickerOpen = true
                // .scene files have provider-dependent MIME types; the library validates contents.
                open.launch(arrayOf("*/*"))
            }) { Text(stringResource(R.string.scene_open)) }
        }
        if (busy) Text(stringResource(R.string.scene_file_working), style = MaterialTheme.typography.bodySmall)
    }
}
