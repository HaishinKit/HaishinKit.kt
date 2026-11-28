package com.haishinkit.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import com.haishinkit.rtmp.RtmpStreamSessionFactory
import com.haishinkit.srt.SrtStreamSessionFactory
import com.haishinkit.stream.StreamSession

class MainActivity : AppCompatActivity() {
    var pictureInPicture by mutableStateOf(false)
        private set

    private var pictureInPictureEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(DebugMcpServer())
        pictureInPicture = isInPictureInPictureMode
        runCatching { HdrPreference.validateGraphics(this, HdrPreference.selected(this)) }.onFailure { cause ->
            HdrPreference.save(this, HdrSettings())
            android.widget.Toast.makeText(this, cause.message, android.widget.Toast.LENGTH_LONG).show()
        }
        RendererPreference.configure(this)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        StreamSession.Builder.registerFactory(RtmpStreamSessionFactory)
        StreamSession.Builder.registerFactory(SrtStreamSessionFactory)
        setContent {
            StudioTheme {
                MainScreen(onApplyHdr = { settings ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { HdrPreference.validate(this@MainActivity, settings) }
                    updatePictureInPicture(false)
                    RendererPreference.stopOutputs()
                    stopService(Intent(this, MediaProjectionService::class.java))
                    HdrPreference.save(this, settings)
                    RendererPreference.configure(this)
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }, onApplyRenderer = { renderer ->
                    RendererPreference.stopOutputs()
                    stopService(Intent(this, MediaProjectionService::class.java))
                    RendererPreference.save(this, renderer)
                    RendererPreference.configure(this)
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                })
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPicture = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pictureInPictureEnabled) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        }
    }

    fun updatePictureInPicture(enabled: Boolean) {
        pictureInPictureEnabled = enabled && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        // Android 17 may remove the activity token before Compose disposes its effects.
        if (isFinishing || isDestroyed || lifecycle.currentState == Lifecycle.State.DESTROYED) return
        val builder = PictureInPictureParams.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(pictureInPictureEnabled)
        }
        setPictureInPictureParams(builder.build())
    }
}
