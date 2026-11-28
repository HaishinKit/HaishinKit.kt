package com.haishinkit.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat

/** Only the uiTest variant can request a capture token for the optional device test. */
class AudioCaptureConsentActivity : Activity() {
    private var requested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requested = savedInstanceState?.getBoolean("requested") == true
        setContentView(android.widget.TextView(this).apply { text = "HaishinKit audio capture test" })
    }

    override fun onResume() {
        super.onResume()
        if (!requested) {
            requested = true
            window.decorView.post {
                val manager = getSystemService(MediaProjectionManager::class.java)
                val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
                startActivityForResult(intent, 1)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("requested", requested)
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(this, Intent(this, AudioCaptureTestService::class.java).putExtra("projection", data))
        } else {
            AudioCaptureTestService.projection.completeExceptionally(IllegalStateException("Projection consent was not granted: request=$requestCode result=$resultCode"))
        }
        finish()
    }
}
