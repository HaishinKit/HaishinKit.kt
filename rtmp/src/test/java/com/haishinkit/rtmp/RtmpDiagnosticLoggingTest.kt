package com.haishinkit.rtmp

import android.util.Log
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RtmpDiagnosticLoggingTest {
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
