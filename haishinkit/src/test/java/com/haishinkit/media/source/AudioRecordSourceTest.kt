package com.haishinkit.media.source

import android.Manifest
import android.media.AudioRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AudioRecordSourceTest {
    @Test
    fun recordingCanRestartAfterClose() =
        runBlocking {
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val source = AudioRecordSource(application)
            val first = requireNotNull(source.audioRecord)
            first.startRecording()

            assertTrue(source.close().isSuccess)
            assertEquals(AudioRecord.STATE_UNINITIALIZED, first.state)
            val second = requireNotNull(source.audioRecord)
            assertNotSame(first, second)
            second.startRecording()
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, second.recordingState)
            assertTrue(source.close().isSuccess)
            assertTrue(source.close().isSuccess)
        }

    @Test
    fun closeReleasesRecorderEvenAfterPermissionIsRevoked() =
        runBlocking {
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val source = AudioRecordSource(application)
            val recorder = requireNotNull(source.audioRecord)
            recorder.startRecording()
            shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)

            assertTrue(source.close().isSuccess)
            assertEquals(AudioRecord.STATE_UNINITIALIZED, recorder.state)
        }
}
