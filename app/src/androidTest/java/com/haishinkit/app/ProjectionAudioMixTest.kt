package com.haishinkit.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaOutput
import com.haishinkit.media.MediaOutputDataSource
import com.haishinkit.media.audio.PcmAudioFormat
import com.haishinkit.media.source.MediaProjectionAudioSource
import com.haishinkit.screen.AssetScreenObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Opt-in screen-consent test. Captures only the synthetic producer UID, never other apps. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ProjectionAudioMixTest {
    @Test fun authorizedPlaybackCaptureMixesWithAssetAndMicrophonePcm() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("projectionAudioTest") == "true")
            val producerPackage = arguments.getString("audioProducerPackage") ?: "com.haishinkit.test"
            val context = instrumentation.targetContext
            val tone = ComponentName(producerPackage, "com.haishinkit.media.audio.AudioMixToneActivity")
            val producerUid = context.packageManager.getApplicationInfo(producerPackage, 0).uid
            val file = File(context.cacheDir, "projection-audio-mix.mp4")
            instrumentation.context.assets.open("audio-mixing.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
            val mixer = MediaMixer(context)
            val assetError = AtomicReference<Throwable?>()
            val asset =
                AssetScreenObject(context).apply {
                    frame = Rect(0, 0, 64, 48)
                    isLooping = true
                    onError = { assetError.set(it) }
                    startReading(Uri.fromFile(file))
                }
            val complete = CountDownLatch(1)
            val samples = ShortArray(48000 * 2)
            var count = 0
            val output =
                object : MediaOutput {
                    override var dataSource: WeakReference<MediaOutputDataSource>? = null
                    private var first = 0L

                    override fun append(buffer: MediaBuffer) {
                        if (first == 0L) first = buffer.timestamp
                        if (buffer.timestamp - first < 500_000 || count >= samples.size) return
                        val input = checkNotNull(buffer.payload)
                        var offset = 0
                        while (offset < input.remaining() && count < samples.size) {
                            samples[count++] = input.getShort(offset)
                            offset += 2
                        }
                        if (count == samples.size) complete.countDown()
                    }
                }
            try {
                context.startActivity(Intent().setComponent(tone).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val toneDeadline = SystemClock.uptimeMillis() + 5000
                while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != producerPackage && SystemClock.uptimeMillis() < toneDeadline) SystemClock.sleep(50)
                AudioCaptureTestService.projection = CompletableFuture()
                context.startActivity(Intent(context, AudioCaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                approveConsent()
                val projection = AudioCaptureTestService.projection.get(10, TimeUnit.SECONDS)
                mixer.screen.frame = Rect(0, 0, 64, 48)
                mixer.screen.addChild(asset)
                mixer.attachAudio(0, AudioCompositionTest.ConstantSource(PcmAudioFormat(48000, 1), 1000)).getOrThrow()
                mixer.attachAudio(1, MediaProjectionAudioSource(context, projection, capturedUid = producerUid)).getOrThrow()
                mixer.attachAudio(asset.audioTrack, asset.audioSource).getOrThrow()
                mixer.registerOutput(output)
                mixer.startRunning()
                assertTrue("No mixed playback capture", complete.await(8, TimeUnit.SECONDS))
                assetError.get()?.let { throw AssertionError("Asset audio/video failed", it) }
                assertEquals("Microphone PCM missing", 1000.0, samples.map { it.toDouble() }.average(), 150.0)
                assertTrue("Asset left channel missing", amplitude(samples, 0, 440) > 1500)
                assertTrue("Asset right channel missing", amplitude(samples, 1, 660) > 1500)
                assertTrue("MediaProjection playback audio missing", amplitude(samples, 0, 1000) > 100)
            } finally {
                asset.close()
                mixer.stopRunning()
                mixer.unregisterOutput(output)
                mixer.dispose()
                context.stopService(Intent(context, AudioCaptureTestService::class.java))
                context.startActivity(Intent().setComponent(tone).putExtra("stop", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                file.delete()
            }
        }

    private fun approveConsent() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 15000
        var last = ""
        while (!AudioCaptureTestService.projection.isDone && SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null && root.packageName?.toString() == "com.android.systemui") {
                val nodes = descendants(root)
                last = nodes.mapNotNull { it.text?.toString() }.joinToString(" | ")
                val entire = nodes.firstOrNull { it.text?.toString() in listOf("Entire screen", "画面全体") }
                val spinner = nodes.firstOrNull { it.className?.toString() == "android.widget.Spinner" }
                val positive = nodes.firstOrNull { it.viewIdResourceName == "android:id/button1" && it.isEnabled && it.text?.toString() !in listOf("Cancel", "キャンセル") }
                val single = nodes.firstOrNull { it.text?.toString() in listOf("A single app", "1 つのアプリ", "1つのアプリ") }
                when {
                    entire != null && positive == null -> clickable(entire)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    spinner != null && single != null -> spinner.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    single != null && positive != null -> clickable(single)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    positive != null -> positive.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            SystemClock.sleep(150)
        }
        assertTrue("Screen capture consent did not complete: $last", AudioCaptureTestService.projection.isDone)
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        result.add(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { result.addAll(descendants(it)) }
        return result
    }

    private fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        when {
            node == null -> null
            node.isClickable -> node
            else -> clickable(node.parent)
        }

    private fun amplitude(
        pcm: ShortArray,
        channel: Int,
        frequency: Int,
    ): Double {
        var real = 0.0
        var imaginary = 0.0
        for (i in 0 until pcm.size / 2) {
            val phase = 2 * Math.PI * frequency * i / 48000
            real += pcm[i * 2 + channel] * cos(phase)
            imaginary += pcm[i * 2 + channel] * sin(phase)
        }
        return sqrt(real * real + imaginary * imaginary) * 4 / pcm.size
    }
}
