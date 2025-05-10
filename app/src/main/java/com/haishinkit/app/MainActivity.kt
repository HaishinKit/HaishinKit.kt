package com.haishinkit.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Messenger
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme

class MainActivity :
    AppCompatActivity(),
    ServiceConnection {
    private val startMediaProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                MediaProjectionService.data = result.data
                Intent(this, MediaProjectionService::class.java).also { intent ->
                    if (Build.VERSION_CODES.O <= Build.VERSION.SDK_INT) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    bindService(
                        intent,
                        this,
                        Context.BIND_AUTO_CREATE,
                    )
                }
                Log.i(toString(), "mediaProjectionManager success")
            }
        }

    private var messenger: Messenger? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }

    override fun onServiceConnected(
        name: ComponentName?,
        binder: IBinder?,
    ) {
        messenger = Messenger(binder)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        messenger = null
    }
}
