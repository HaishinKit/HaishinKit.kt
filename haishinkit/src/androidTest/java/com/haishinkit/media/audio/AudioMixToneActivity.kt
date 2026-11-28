package com.haishinkit.media.audio

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.annotation.RequiresApi

/** Optional cross-UID playback-capture fixture. Never opens a microphone or a network stream. */
@RequiresApi(29)
class AudioMixToneActivity : Activity() {
    private var track: AudioTrack? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("stop", false)) {
            finish()
            return
        }
        setContentView(
            TextView(this).apply {
                text = "HaishinKit synthetic audio test · 1 kHz"
                textSize = 24f
            },
        )
        handler.postDelayed(timeout, 30000)
        val samples = ShortArray(48000 * 2) { i -> (8192 * kotlin.math.sin(2 * Math.PI * 1000 * (i / 2) / 48000)).toInt().toShort() }
        track =
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build().apply {
                    write(samples, 0, samples.size)
                    setLoopPoints(0, 48000, -1)
                    setVolume(.1f)
                    play()
                }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("stop", false)) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        track?.let {
            it.stop()
            it.release()
        }
        track = null
        super.onDestroy()
    }
}
