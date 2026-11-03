package com.haishinkit.srt

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtStreamSessionTest {
    @Test
    fun builderCreatesReusableSessionAndCloseCancelsPendingListener() =
        runBlocking {
            withTimeout(10_000) {
                StreamSession.Builder.registerFactory(SrtStreamSessionFactory)
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val session =
                    StreamSession.Builder(context, Uri.parse("srt://127.0.0.1:0?mode=listener"))
                        .setMode(StreamSession.Mode.PLAYBACK).build()
                try {
                    assertTrue(session.stream is SrtStream)
                    assertFalse(session.isConnected)
                    repeat(2) {
                        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.connect() } }
                        session.readyState.first { it == StreamSession.ReadyState.CONNECTING }
                        assertTrue(session.connect().isFailure)
                        assertTrue(session.close().isSuccess)
                        assertTrue(pending.await().exceptionOrNull() is CancellationException)
                        assertTrue(session.readyState.value == StreamSession.ReadyState.CLOSED)
                        assertTrue(session.close().isSuccess)
                    }
                } finally {
                    session.close()
                    session.stream.dispose()
                }
            }
        }
}
