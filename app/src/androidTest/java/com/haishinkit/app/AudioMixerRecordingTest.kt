package com.haishinkit.app

import android.graphics.Rect
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaOutput
import com.haishinkit.media.MediaOutputDataSource
import com.haishinkit.media.MediaRecorder
import com.haishinkit.media.audio.PcmAudioFormat
import com.haishinkit.screen.AssetScreenObject
import com.haishinkit.screen.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AudioMixerRecordingTest {
    @Test fun recordingAfterTheFinalInputIsDetachedKeepsBothMuxerTracks() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val previous = Screen.factory
            Screen.factory = { Screen.createOpenGL(it) }
            val input = File(context.cacheDir, "recording-asset.mp4")
            val output = File(context.cacheDir, "recording-after-audio-detach.mp4")
            instrumentation.context.assets.open("audio-mixing.mp4").use { source -> input.outputStream().use { source.copyTo(it) } }
            val mixer = MediaMixer(context)
            val recorder = MediaRecorder(context)
            val asset =
                AssetScreenObject(context).apply {
                    frame = Rect(0, 0, 128, 128)
                    isLooping = true
                    startReading(Uri.fromFile(input))
                }
            val ready = CountDownLatch(1)
            val probe =
                object : MediaOutput {
                    override var dataSource: WeakReference<MediaOutputDataSource>? = null

                    override fun append(buffer: MediaBuffer) {
                        ready.countDown()
                    }
                }
            try {
                mixer.screen.frame = Rect(0, 0, 128, 128)
                mixer.screen.addChild(asset)
                mixer.attachAudio(0, AudioCompositionTest.ConstantSource(PcmAudioFormat(48000, 1), 1000)).getOrThrow()
                mixer.registerOutput(probe)
                mixer.startRunning()
                assertTrue(ready.await(3, TimeUnit.SECONDS))
                mixer.attachAudio(0, null).getOrThrow()
                assertTrue("A running silent clock must advertise audio to a new recorder", mixer.hasAudio)
                mixer.registerOutput(recorder)
                recorder.videoSetting.width = 128
                recorder.videoSetting.height = 128
                recorder.videoSetting.frameRate = 15
                recorder.startRecording(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                SystemClock.sleep(1500)
                recorder.stopRecording()
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(output.absolutePath)
                    assertEquals(2, extractor.trackCount)
                    val mimeTypes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                    assertTrue(mimeTypes.any { it?.startsWith("audio/") == true })
                    assertTrue(mimeTypes.any { it?.startsWith("video/") == true })
                    for (track in 0 until extractor.trackCount) {
                        extractor.selectTrack(track)
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        assertTrue("Empty recorded track $track", extractor.readSampleData(ByteBuffer.allocate(1024 * 1024), 0) > 0)
                        extractor.unselectTrack(track)
                    }
                } finally {
                    extractor.release()
                }
            } finally {
                if (recorder.isRecording) recorder.stopRecording()
                mixer.unregisterOutput(recorder)
                mixer.unregisterOutput(probe)
                asset.close()
                mixer.stopRunning()
                mixer.dispose()
                Screen.factory = previous
                input.delete()
                output.delete()
            }
        }
}
