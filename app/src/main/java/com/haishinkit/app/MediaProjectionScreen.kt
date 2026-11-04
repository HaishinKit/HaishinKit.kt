package com.haishinkit.app

import android.app.Activity.RESULT_OK
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Suppress("ktlint:standard:function-naming")
@Composable
fun MediaProjectionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val binding = remember { MediaProjectionBinding(context.applicationContext) }
    val startMediaProjection =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                binding.bind(MediaProjectionService.startService(context, result.resultCode, result.data))
            }
        }

    DisposableEffect(binding) {
        onDispose { binding.dispose() }
    }
    LaunchedEffect(binding) {
        if (MediaProjectionService.isRunning(context)) {
            binding.bind(Intent(context, MediaProjectionService::class.java))
        }
    }

    Box(
        modifier = modifier.safeDrawingPadding().fillMaxSize().padding(8.dp),
    ) {
        if (binding.isStarted) {
            CircularProgressIndicator(
                modifier = Modifier.width(64.dp).align(Alignment.Center),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        Button(
            modifier = Modifier.align(Alignment.BottomEnd),
            enabled = !binding.isStopping,
            onClick = {
                if (binding.isStarted) {
                    binding.stop()
                } else {
                    val manager = context.getSystemService(Service.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startMediaProjection.launch(manager.createScreenCaptureIntent())
                }
            },
        ) {
            Text(
                if (binding.isStopping) {
                    "Stopping…"
                } else if (binding.isStarted) {
                    "STOP"
                } else {
                    "GO LIVE"
                },
            )
        }
    }
}
