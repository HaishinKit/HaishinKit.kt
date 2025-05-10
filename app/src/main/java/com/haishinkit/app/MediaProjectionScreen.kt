package com.haishinkit.app

import android.app.Activity.RESULT_OK
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val TAG = "MediaProjectionScreen"

@Suppress("ktlint:standard:function-naming")
@Composable
fun MediaProjectionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var service: MediaProjectionService? by remember { mutableStateOf(null) }

    val connection =
        remember {
            object : ServiceConnection {
                override fun onServiceConnected(
                    p0: ComponentName?,
                    p1: IBinder?,
                ) {
                }

                override fun onServiceDisconnected(p0: ComponentName?) {
                }
            }
        }

    val startMediaProjection =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                MediaProjectionService.data = result.data
                Intent(context, MediaProjectionService::class.java).also { intent ->
                    if (Build.VERSION_CODES.O <= Build.VERSION.SDK_INT) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    context.bindService(
                        intent,
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                }
                Log.i(TAG, "mediaProjectionManager success")
            }
        }

    Box(
        modifier =
            modifier
                .safeContentPadding()
                .fillMaxSize()
                .padding(8.dp),
    ) {
        CircularProgressIndicator(
            modifier =
                Modifier
                    .width(64.dp)
                    .align(Alignment.Center),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Button(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd),
            onClick = {
                val mediaProjectionManager =
                    context.getSystemService(Service.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
            },
        ) {
            Text("GO LIVE")
        }
    }
}
