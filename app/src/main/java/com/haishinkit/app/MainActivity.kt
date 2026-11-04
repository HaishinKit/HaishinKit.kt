package com.haishinkit.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import com.haishinkit.rtmp.RtmpStreamSessionFactory
import com.haishinkit.srt.SrtLogger
import com.haishinkit.srt.SrtStreamSessionFactory
import com.haishinkit.stream.StreamSession

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SrtLogger.level = SrtLogger.Level.DEBUG
        // Register native defaults before excluding the noisy epoll areas.
        SrtLogger.functionalAreas = SrtLogger.FunctionalArea.entries.toSet()
        SrtLogger.functionalAreas -= setOf(SrtLogger.FunctionalArea.EPOLL_API, SrtLogger.FunctionalArea.EPOLL_UPD)
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
}
