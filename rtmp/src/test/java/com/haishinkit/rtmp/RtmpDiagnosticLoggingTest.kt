package com.haishinkit.rtmp

import android.graphics.Bitmap
import android.util.Log
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RtmpDiagnosticLoggingTest {
    private val originalScreenFactory = Screen.factory

    @Before fun isolateCommandLoggingFromGraphics() {
        Screen.factory = { context ->
            object : Screen(context) {
                override fun attachVideo(
                    track: Int,
                    video: VideoSource?,
                ) {}

                override fun readPixels(lambda: (Bitmap?) -> Unit) {}

                override fun bind(screenObject: ScreenObject) {}

                override fun unbind(screenObject: ScreenObject) {}
            }
        }
    }

    @After fun restoreGraphicsFactory() {
        Screen.factory = originalScreenFactory
    }

    @Test fun malformedStatusCodesDoNotDumpNestedServerPayloads() {
        val responder = createStreamResponder()
        for (code in listOf(mapOf("token" to "nested-secret"), listOf("nested-secret"))) {
            ShadowLog.clear()
            responder.onStatus(listOf(mapOf("code" to code)))
            val warnings = ShadowLog.getLogsForTag("RtmpConnection").filter { it.type == Log.WARN }
            assertTrue(warnings.isNotEmpty())
            assertFalse(warnings.any { it.msg.contains("nested-secret") || it.msg.contains("token") })
        }
    }

    @Test fun ordinaryStatusCodesRemainAvailableForDiagnosis() {
        val responder = createStreamResponder()
        ShadowLog.clear()
        responder.onStatus(listOf(mapOf("code" to "NetConnection.Call.Failed", "description" to "server-private-detail")))
        val warnings = ShadowLog.getLogsForTag("RtmpConnection").filter { it.type == Log.WARN }
        assertTrue(warnings.any { it.msg.contains("NetConnection.Call.Failed") })
        assertFalse(warnings.any { it.msg.contains("server-private-detail") })
    }

    private fun createStreamResponder(): Responder {
        val connection = RtmpConnection()
        val stream = RtmpStream(RuntimeEnvironment.getApplication(), connection)
        // Exercise the normal command responder without opening a network connection.
        RtmpSocket::class.java.getDeclaredField("isConnected").apply { isAccessible = true }.setBoolean(connection.socket, true)
        connection.createStream(stream)
        return connection.responders.values.single()
    }
}
