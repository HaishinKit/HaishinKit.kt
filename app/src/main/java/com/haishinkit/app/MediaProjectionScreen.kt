package com.haishinkit.app

import android.app.Activity.RESULT_OK
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Suppress("ktlint:standard:function-naming")
@Composable
fun MediaProjectionScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
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

    Box(modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StudioHeader(stringResource(R.string.studio_share), stringResource(R.string.studio_share_subtitle), onOpenSettings)
            StudioSection(
                stringResource(if (binding.isStarted) R.string.studio_share_active else R.string.studio_share_ready),
                stringResource(if (binding.isStarted) R.string.studio_share_active_help else R.string.studio_share_help),
            ) {
                Icon(painterResource(R.drawable.studio_screen), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally))
                Text(stringResource(R.string.studio_share_destination), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    modifier = Modifier.fillMaxWidth(),
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
                        stringResource(
                            if (binding.isStopping) {
                                R.string.studio_stopping
                            } else if (binding.isStarted) {
                                R.string.studio_share_stop
                            } else {
                                R.string.studio_share_start
                            },
                        ),
                    )
                }
                if (BuildConfig.VQMETRIX_AVAILABLE && binding.isStarted && !binding.isStopping) {
                    androidx.compose.material3.OutlinedButton(onClick = { binding.saveQuality() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.vq_capture_action))
                    }
                }
            }
        }
    }
}
