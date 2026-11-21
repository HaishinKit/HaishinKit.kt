package com.haishinkit.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun IngestDestinationDialog(
    discovery: BonjourIngestDiscovery,
    onSelect: (IngestEndpoint) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by discovery.state.collectAsState()
    var allowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED)
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            allowed = granted
        }
    LifecycleStartEffect(discovery, allowed) {
        if (allowed) discovery.start()
        onStopOrDispose { discovery.stop() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ingest_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ingest_help))
                if (!allowed) {
                    TextButton(onClick = { permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }) {
                        Text(stringResource(R.string.studio_network_allow))
                    }
                } else {
                    if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.failed) Text(stringResource(R.string.ingest_discovery_error))
                    if (state.endpoints.isEmpty()) Text(stringResource(R.string.ingest_empty))
                    state.endpoints.forEach { endpoint ->
                        TextButton(onClick = {
                            discovery.stop()
                            onSelect(endpoint)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(endpoint.name)
                                Text("${endpoint.host}:${endpoint.port}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { discovery.start() }, enabled = allowed) { Text(stringResource(R.string.ingest_refresh)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.studio_close_tools)) } },
    )
}
