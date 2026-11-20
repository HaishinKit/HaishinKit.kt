package com.haishinkit.app.vqmetrix

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.haishinkit.screen.Screen
import com.haishinkit.vqmetrix.BatchConfig
import com.haishinkit.vqmetrix.BatchPipeline
import com.haishinkit.vqmetrix.ScreenReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Example-owned controller: one bounded snapshot session from an existing mixer. */
object MixerCapture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var source: String? = null
    private var sampling = false

    @JvmStatic
    fun start(
        context: Context,
        screen: Screen,
        source: String,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { start(context, screen, source) }
            return
        }
        val app = context.applicationContext
        if (job?.isActive == true) {
            Toast.makeText(app, R.string.vq_capture_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val preferences = app.getSharedPreferences("vqmetrix-capture", Context.MODE_PRIVATE)
        val config =
            runCatching {
                BatchConfig(
                    640,
                    480,
                    15,
                    preferences.getInt("seconds", 3),
                    BatchConfig.parseBitrates(preferences.getString("bitrates", "0.5,1,2").orEmpty()),
                    preferences.getString("codec", "h264").orEmpty(),
                    false,
                )
            }.getOrElse {
                Toast.makeText(app, R.string.vq_invalid, Toast.LENGTH_LONG).show()
                return
            }
        this.source = source
        sampling = true
        Toast.makeText(app, R.string.vq_capture_started, Toast.LENGTH_LONG).show()
        job =
            scope.launch {
                try {
                    val result =
                        BatchPipeline(app).create(config, { progress ->
                            if (progress.stage != "reference") handler.post { sampling = false }
                        }, {}, ScreenReference(screen, source))
                    Toast.makeText(app, app.getString(R.string.vq_capture_saved, result.runId.take(8)), Toast.LENGTH_LONG).show()
                } catch (_: CancellationException) {
                    Toast.makeText(app, R.string.vq_cancelled, Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Toast.makeText(app, app.getString(R.string.vq_error, error.message), Toast.LENGTH_LONG).show()
                } finally {
                    sampling = false
                    this@MixerCapture.source = null
                    job = null
                }
            }
    }

    @JvmStatic
    fun stop(source: String) {
        handler.post {
            if (this.source == source && sampling) job?.cancel()
        }
    }
}
