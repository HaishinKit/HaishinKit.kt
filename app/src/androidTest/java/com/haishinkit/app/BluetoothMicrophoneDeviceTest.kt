package com.haishinkit.app

import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.Codec
import com.haishinkit.media.AudioMixerSettings
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaOutput
import com.haishinkit.media.MediaOutputDataSource
import com.haishinkit.media.source.AudioRecordSource
import com.haishinkit.media.source.PcmAudioSource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/** Opt-in physical capture; no PCM or encoded audio is persisted or sent over a network. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class BluetoothMicrophoneDeviceTest {
    @Test fun headsetDisconnectAndReconnectKeepTheClientAndEncoderFormatsFixed() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("bluetoothMicrophoneTest") == "true")
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val manager = context.getSystemService(AudioManager::class.java)
            val previousMode = manager.mode
            check(previousMode != AudioManager.MODE_IN_CALL) { "A call is active" }
            val measurements = Measurements()
            val source =
                AudioRecordSource(context).apply {
                    sampleRate = 48000
                    preferBluetoothMicrophone = true
                }
            // Read the existing recorder without invoking the public lazy-creation getter.
            val recorderField = AudioRecordSource::class.java.getDeclaredField("cachedAudioRecord").apply { isAccessible = true }

            fun recorder(): AudioRecord? = recorderField.get(source) as? AudioRecord

            fun route(): Int? = runCatching { recorder()?.routedDevice?.type }.getOrNull()

            fun routeDetails(): JSONObject =
                runCatching {
                    val record = recorder()
                    val configuration = record?.activeRecordingConfiguration
                    JSONObject().put("inputType", record?.routedDevice?.type)
                        .put("inputName", record?.routedDevice?.productName?.toString())
                        .put("clientRate", record?.sampleRate).put("clientChannels", record?.channelCount)
                        .put("deviceFormatRate", configuration?.format?.sampleRate)
                        .put("clientSilenced", configuration?.isClientSilenced)
                        .put("microphoneMuted", manager.isMicrophoneMute)
                        .put("session", record?.audioSessionId).put("mode", manager.mode)
                }.getOrElse { JSONObject().put("routeError", it.javaClass.simpleName) }

            fun report(
                label: String,
                data: JSONObject,
            ) {
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nBT_AUDIO $label $data\n") })
            }

            fun awaitRoute(
                label: String,
                expected: (Int?) -> Boolean,
                timeoutMs: Long = 20000,
            ) {
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (expected(route())) return
                    SystemClock.sleep(100)
                }
                report(label, routeDetails())
                throw AssertionError("$label: input route was ${route()}")
            }

            fun measure(label: String) {
                measurements.beginWindow()
                val started = SystemClock.elapsedRealtime()
                SystemClock.sleep(4000)
                val elapsed = SystemClock.elapsedRealtime() - started
                val data = measurements.snapshot().put("elapsedMs", elapsed).put("route", routeDetails())
                report(label, data)
                assertEquals(48000, recorder()?.sampleRate)
                assertEquals(1, recorder()?.channelCount)
                assertTrue("Android silenced the microphone", !data.getJSONObject("route").optBoolean("clientSilenced"))
                assertTrue("Microphone is muted", !manager.isMicrophoneMute)
                assertTrue("No microphone PCM in $label", data.getLong("rawFrames") > 48000)
                assertTrue("Mixed output stopped in $label", data.getLong("mixedFrames") > elapsed * 48 * 9 / 10)
                assertEquals("Mixed PCM shape changed", 0, data.getInt("invalidMixedBuffers"))
                assertTrue("Mixed timeline changed", !data.getBoolean("mixedTimeError"))
                assertTrue("Encoder timeline changed", !data.getBoolean("encodedTimeError"))
                assertEquals(48000, data.getInt("encodedRate"))
                assertEquals(2, data.getInt("encodedChannels"))
                assertEquals("Encoder format restarted", 1, data.getInt("formatChanges"))
                assertTrue("Encoder stopped", data.getLong("encodedPackets") > 0)
                measurements.error.get()?.let { throw AssertionError("Codec failed", it) }
            }
            val input =
                object : PcmAudioSource by source {
                    override fun read(track: Int): MediaBuffer = source.read(track).also { measurements.raw(it.payload) }
                }
            val mixer = MediaMixer(context).apply { audioMixerSettings = AudioMixerSettings(sampleRate = 48000, channels = 2) }
            val encoder =
                AudioCodec().apply {
                    sampleRate = 48000
                    channelCount = 2
                    bitRate = 96000
                    listener = measurements
                }
            val output =
                object : MediaOutput {
                    override var dataSource: WeakReference<MediaOutputDataSource>? = null

                    override fun append(buffer: MediaBuffer) {
                        measurements.mixed(buffer)
                        buffer.payload?.let { encoder.append(it) }
                    }
                }
            try {
                context.startActivity(Intent(context, BluetoothMicrophoneTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val foregroundDeadline = SystemClock.elapsedRealtime() + 5000
                var foreground = false
                while (!foreground && SystemClock.elapsedRealtime() < foregroundDeadline) {
                    instrumentation.runOnMainSync {
                        foreground = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is BluetoothMicrophoneTestActivity }
                    }
                    if (!foreground) SystemClock.sleep(100)
                }
                assertTrue("Microphone test activity is not foreground", foreground)
                report("before", JSONObject().put("mode", previousMode).put("communicationTypes", manager.availableCommunicationDevices.map { it.type }.toString()))
                mixer.attachAudio(0, input).getOrThrow()
                mixer.registerOutput(output)
                encoder.startRunning()
                mixer.startRunning()
                awaitRoute("initial-headset", { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it == AudioDeviceInfo.TYPE_BLE_HEADSET })
                SystemClock.sleep(500)
                measure("bluetooth")
                measurements.beginWindow()
                shell("cmd bluetooth_manager disable")
                awaitRoute("headset-disconnected", { it == AudioDeviceInfo.TYPE_BUILTIN_MIC })
                report("disconnect-transition", measurements.snapshot().put("route", routeDetails()))
                SystemClock.sleep(500)
                measure("internal-microphone")
                measurements.beginWindow()
                shell("cmd bluetooth_manager enable")
                awaitRoute("headset-reconnected", { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it == AudioDeviceInfo.TYPE_BLE_HEADSET }, 40000)
                report("reconnect-transition", measurements.snapshot().put("route", routeDetails()))
                SystemClock.sleep(500)
                measure("bluetooth-restored")
            } finally {
                report("final-route", routeDetails())
                mixer.stopRunning()
                mixer.attachAudio(0, null)
                mixer.unregisterOutput(output)
                encoder.stopRunning()
                mixer.dispose()
                shell("cmd bluetooth_manager enable")
                instrumentation.runOnMainSync {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<BluetoothMicrophoneTestActivity>().forEach { it.finish() }
                }
                report("cleanup", JSONObject().put("mode", manager.mode).put("recordRetained", recorder() != null))
            }
        }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private class Measurements : Codec.Listener {
        val error = AtomicReference<Throwable?>()
        private var rawFrames = 0L
        private var rawEnergy = 0.0
        private var rawSamples = 0L
        private var rawEmpty = 0
        private var lastRawNs = 0L
        private var maxRawGapNs = 0L
        private var mixedFrames = 0L
        private var invalidMixedBuffers = 0
        private var lastMixedPts = -1L
        private var mixedTimeError = false
        private var encodedPackets = 0L
        private var lastEncodedPts = -1L
        private var encodedTimeError = false
        private var encodedRate = 0
        private var encodedChannels = 0
        private var formatChanges = 0

        @Synchronized fun beginWindow() {
            rawFrames = 0
            rawSamples = 0
            rawEnergy = 0.0
            rawEmpty = 0
            maxRawGapNs = 0
            mixedFrames = 0
            invalidMixedBuffers = 0
            encodedPackets = 0
        }

        @Synchronized fun raw(buffer: ByteBuffer?) {
            if (buffer == null || !buffer.hasRemaining()) {
                rawEmpty++
                return
            }
            val now = System.nanoTime()
            if (lastRawNs != 0L) maxRawGapNs = maxOf(maxRawGapNs, now - lastRawNs)
            lastRawNs = now
            val pcm = buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            rawFrames += pcm.remaining()
            while (pcm.hasRemaining()) {
                val value = pcm.get().toDouble()
                rawEnergy += value * value
                rawSamples++
            }
        }

        @Synchronized fun mixed(buffer: MediaBuffer) {
            val bytes = buffer.payload?.remaining() ?: 0
            if (bytes != 1920) invalidMixedBuffers++
            mixedFrames += bytes / 4
            if (lastMixedPts >= 0 && buffer.timestamp - lastMixedPts != 10000L) mixedTimeError = true
            lastMixedPts = buffer.timestamp
        }

        @Synchronized fun snapshot(): JSONObject =
            JSONObject()
                .put("rawFrames", rawFrames).put("rawRms", if (rawSamples > 0) kotlin.math.sqrt(rawEnergy / rawSamples) else 0)
                .put("emptyReads", rawEmpty).put("maxRawGapMs", maxRawGapNs / 1_000_000.0)
                .put("mixedFrames", mixedFrames).put("invalidMixedBuffers", invalidMixedBuffers).put("mixedTimeError", mixedTimeError)
                .put("encodedPackets", encodedPackets).put("encodedTimeError", encodedTimeError)
                .put("encodedRate", encodedRate).put("encodedChannels", encodedChannels).put("formatChanges", formatChanges)

        override fun onInputBufferAvailable(
            mime: String,
            codec: MediaCodec,
            index: Int,
        ) {}

        @Synchronized override fun onFormatChanged(
            mime: String,
            mediaFormat: MediaFormat,
        ) {
            encodedRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            encodedChannels = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            formatChanges++
        }

        @Synchronized override fun onSampleOutput(
            mime: String,
            index: Int,
            info: MediaCodec.BufferInfo,
            buffer: ByteBuffer,
        ): Boolean {
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                if (lastEncodedPts >= 0 && info.presentationTimeUs <= lastEncodedPts) encodedTimeError = true
                lastEncodedPts = info.presentationTimeUs
                encodedPackets++
            }
            return true
        }

        override fun onError(
            mime: String,
            cause: MediaCodec.CodecException,
        ) {
            error.set(cause)
        }
    }
}
