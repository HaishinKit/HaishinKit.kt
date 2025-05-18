package com.haishinkit.app

import android.app.Activity.RESULT_OK
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val TAG = "MediaProjectionScreen"

@Suppress("ktlint:standard:function-naming")
@Composable
fun MediaProjectionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var messenger: Messenger? = remember { null }

    var started = remember { mutableStateOf(false) }

    val connection =
        remember {
            object : ServiceConnection {
                override fun onServiceConnected(
                    componentName: ComponentName?,
                    binder: IBinder?,
                ) {
                    messenger = Messenger(binder)
                    messenger?.send(Message.obtain(null, MediaProjectionService.MSG_CONNECT))
                    Log.i(TAG, "onServiceConnected()")
                }

                override fun onServiceDisconnected(p0: ComponentName?) {
                    messenger = null
                    context.unbindService(this)
                    Log.i(TAG, "onServiceDisconnected")
                }
            }
        }

    val startMediaProjection =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                var intent =
                    MediaProjectionService.startService(context, result.resultCode, result.data)
                context.bindService(intent, connection, 0)
                Log.i(TAG, "mediaProjectionManager success")
            }
        }

    LaunchedEffect(Unit) {
        if (MediaProjectionService.isRunning(context)) {
            Intent(context, MediaProjectionService::class.java).also { intent ->
                context.bindService(intent, connection, 0)
                started.value = true
            }
        }
    }

    Box(
        modifier =
            modifier
                .safeDrawingPadding()
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
                if (started.value) {
                    messenger?.send(Message.obtain(null, MediaProjectionService.MSG_CLOSE))
                    started.value = false
                } else {
                    val mediaProjectionManager =
                        context.getSystemService(Service.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
                    started.value = true
                }
            },
        ) {
            if (started.value) {
                Text("STOP")
                messenger?.send(Message.obtain(null, MediaProjectionService.MSG_CLOSE))
            } else {
                Text("GO LIVE")
            }
        }
    }
}
