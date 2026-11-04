package com.haishinkit.rtmp

import android.net.Uri
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RtmpStreamSessionCloseTest {
    @Test
    fun closeDuringHandshakeCompletesConnectAndClosesTransport() = verifyShutdown(cancelWaiter = false)

    @Test
    fun closeStillClosesTransportAfterConnectWaiterWasCancelled() = verifyShutdown(cancelWaiter = true)

    @Test
    fun closeDuringTlsHandshakeClosesTransport() = verifyShutdown(cancelWaiter = false, secure = true)

    private fun verifyShutdown(
        cancelWaiter: Boolean,
        secure: Boolean = false,
    ) =
        runBlocking {
            ServerSocket(0).use { server ->
                server.soTimeout = 3000
                val session =
                    RtmpStreamSession(
                        RuntimeEnvironment.getApplication(),
                        Uri.parse("${if (secure) "rtmps" else "rtmp"}://127.0.0.1:${server.localPort}/live/test"),
                        StreamSession.Mode.PUBLISH,
                    )
                val pending = async(start = CoroutineStart.UNDISPATCHED) { session.connect() }
                try {
                    server.accept().use { peer ->
                        peer.soTimeout = 3000
                        val input = peer.getInputStream()
                        assertEquals(if (secure) 22 else 3, input.read()) // TLS ClientHello or RTMP C0; handshake remains incomplete.
                        if (cancelWaiter) pending.cancelAndJoin()
                        assertTrue(withTimeout(3000) { session.close() }.isSuccess)
                        if (!cancelWaiter) assertTrue(withTimeout(3000) { pending.await() }.isFailure)
                        assertEquals(StreamSession.ReadyState.CLOSED, session.readyState.value)
                        while (input.read() != -1) { /* Drain the rest of the already-sent C1. */ }
                        assertTrue(withTimeout(3000) { session.close() }.isSuccess)
                    }
                } finally {
                    session.close()
                    pending.cancelAndJoin()
                }
            }
        }
}
