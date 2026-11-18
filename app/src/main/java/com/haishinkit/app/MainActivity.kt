package com.haishinkit.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.haishinkit.rtmp.RtmpStreamSessionFactory
import com.haishinkit.srt.SrtStreamSessionFactory
import com.haishinkit.stream.StreamSession

class MainActivity : AppCompatActivity() {
    var pictureInPicture by mutableStateOf(false)
        private set

    private var pictureInPictureEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RendererPreference.configure(this)
        enableEdgeToEdge()
        StreamSession.Builder.registerFactory(RtmpStreamSessionFactory)
        StreamSession.Builder.registerFactory(SrtStreamSessionFactory)
        setContent {
            MaterialTheme {
                MainScreen(onApplyRenderer = { renderer ->
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
        val builder = PictureInPictureParams.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(pictureInPictureEnabled)
        }
        setPictureInPictureParams(builder.build())
    }
}
