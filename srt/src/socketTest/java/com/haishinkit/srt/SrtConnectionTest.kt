package com.haishinkit.srt

import com.haishinkit.srt.ts.TSAudioConfig
import com.haishinkit.srt.ts.TSCodec
import com.haishinkit.srt.ts.TSReader
import com.haishinkit.srt.ts.TSSample
import com.haishinkit.srt.ts.TSWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.DatagramSocket
import java.net.URI

class SrtConnectionTest {
    @Test
    fun cancellingStreamReceptionReleasesReaderAndKeepsConnectionOpen() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { caller, listener ->
                    val owner = Any()
                    listener.claimStream(owner)
                    val receiving =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            listener.streamInputs(owner).collect { }
                        }
                    receiving.cancelAndJoin()
                    listener.releaseStream(owner)
                    assertTrue(listener.isConnected)
                    caller.send(byteArrayOf(42))
                    assertArrayEquals(byteArrayOf(42), listener.receive())
                }
            }
        }

    @Test
    fun transportCarriesMuxedAudioAcrossSrtMessageBoundaries() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { caller, listener ->
                    val owner = Any()
                    caller.claimStream(owner)
                    val writer = TSWriter(setOf(TSCodec.AAC))
                    val reader = TSReader()
                    val payload = ByteArray(4096) { (it % 251).toByte() }
                    val wire = writer.write(TSSample(TSCodec.AAC, payload, 0, audio = TSAudioConfig(48000, 2)))
                    caller.sendStream(owner, wire)
                    val samples = mutableListOf<TSSample>()
                    repeat((wire.size + 1315) / 1316) { samples.addAll(reader.read(listener.receive())) }
                    assertArrayEquals(payload, samples.single().data)
                    caller.releaseStream(owner)
                    assertTrue(caller.isConnected)
                }
            }
        }

    @Before
    fun requireNativeRuntime() {
        val android = System.getProperty("java.vm.name") == "Dalvik"
        val host =
            System.getProperty("java.library.path").orEmpty().split(File.pathSeparator).any {
                File(it, System.mapLibraryName("srt")).isFile
            }
        assumeTrue("Run on Android or supply a host JNI directory", android || host)
    }

    @Test
    fun encryptedCallerAndListenerExchangeMessagesAndStatistics() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair(SrtConnection.Options(passphrase = "connection-secret")) { caller, listener ->
                    assertEquals("1.5.7", SrtConnection.version)
                    assertTrue(caller.isConnected && listener.isConnected)
                    assertEquals(SrtConnection.Mode.CALLER, caller.mode)
                    assertEquals(SrtConnection.Mode.LISTENER, listener.mode)
                    val payload = ByteArray(2649) { it.toByte() }
                    caller.send(payload)
                    val chunks = List(3) { listener.receive() }
                    assertArrayEquals(payload, chunks.fold(byteArrayOf()) { all, next -> all + next })
                    assertTrue(caller.performanceData!!.bytesSent >= payload.size)
                    assertTrue(listener.performanceData!!.bytesReceived >= payload.size)
                }
            }
        }

    @Test
    fun idlePeerDisconnectUpdatesStateWithoutReceiving() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { caller, listener ->
                    caller.close()
                    listener.readyState.first { it == SrtConnection.ReadyState.CLOSED }
                    assertFalse(listener.isConnected)
                    assertNull(listener.uri)
                    assertNull(listener.performanceData)
                }
            }
        }

    @Test
    fun closingListenerCancelsSetupWithoutCancellingCallerScope() =
        runBlocking {
            withTimeout(5000) {
                SrtConnection(this).use { connection ->
                    val pending = async { runCatching { connection.connect("srt://:0?mode=listener") } }
                    connection.readyState.first { it == SrtConnection.ReadyState.LISTENING }
                    assertTrue(connection.localAddress!!.port > 0)
                    connection.close()
                    assertTrue(pending.await().exceptionOrNull() is CancellationException)
                    assertTrue(coroutineContext[Job]!!.isActive)
                    assertEquals(SrtConnection.ReadyState.CLOSED, connection.readyState.value)
                }
            }
        }

    @Test
    fun cancellingOwnerScopeClosesPendingSetup() =
        runBlocking {
            withTimeout(5000) {
                val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    SrtConnection(owner).use { connection ->
                        val pending = async { runCatching { connection.connect("srt://127.0.0.1:0?mode=listener") } }
                        connection.readyState.first { it == SrtConnection.ReadyState.LISTENING }
                        owner.cancel()
                        assertTrue(pending.await().exceptionOrNull() is CancellationException)
                        assertEquals(SrtConnection.ReadyState.CLOSED, connection.readyState.value)
                    }
                } finally {
                    owner.cancel()
                }
            }
        }

    @Test
    fun finiteInputCollectionClosesOnlyItsPeerGeneration() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { caller, listener ->
                    caller.send(byteArrayOf(42))
                    assertArrayEquals(byteArrayOf(42), listener.inputs.take(1).toList().single())
                    assertEquals(SrtConnection.ReadyState.CLOSED, listener.readyState.value)
                    caller.readyState.first { it == SrtConnection.ReadyState.CLOSED }
                }
            }
        }

    @Test
    fun duplicateReadersAndConnectDoNotCloseActivePeer() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { caller, listener ->
                    val pending = async(start = CoroutineStart.UNDISPATCHED) { listener.receive() }
                    assertTrue(runCatching { listener.inputs.take(1).toList() }.exceptionOrNull() is IllegalStateException)
                    assertTrue(runCatching { caller.connect(caller.uri!!) }.exceptionOrNull() is IllegalStateException)
                    assertTrue(caller.isConnected && listener.isConnected)
                    caller.send(byteArrayOf(7))
                    assertArrayEquals(byteArrayOf(7), pending.await())
                }
            }
        }

    @Test
    fun connectionInstancesReconnectAndOldReceiveCannotCloseNewPeer() =
        runBlocking {
            withTimeout(10_000) {
                SrtConnection(this).use { listener ->
                    SrtConnection(this).use { caller ->
                        repeat(2) {
                            val accepting = async { listener.connect("srt://127.0.0.1:0?mode=listener") }
                            listener.readyState.first { it == SrtConnection.ReadyState.LISTENING }
                            caller.connect("srt://127.0.0.1:${listener.localAddress!!.port}")
                            accepting.await()
                            caller.send(byteArrayOf(it.toByte()))
                            assertArrayEquals(byteArrayOf(it.toByte()), listener.receive())
                            val oldReceive = async(start = CoroutineStart.UNDISPATCHED) { runCatching { listener.receive() } }
                            listener.close()
                            caller.close()
                            assertTrue(oldReceive.await().isFailure)
                            assertNull(caller.uri)
                            assertEquals(SrtConnection.ReadyState.CLOSED, listener.readyState.value)
                        }
                    }
                }
            }
        }

    @Test
    fun failedConnectResetsPublicState() =
        runBlocking {
            withTimeout(5000) {
                DatagramSocket(0).use { unavailable ->
                    SrtConnection(this, SrtConnection.Options(connectTimeoutMs = 200)).use { connection ->
                        val failure = runCatching { connection.connect("srt://127.0.0.1:${unavailable.localPort}") }
                        assertTrue(failure.isFailure)
                        assertEquals(SrtConnection.ReadyState.CLOSED, connection.readyState.value)
                        assertNull(connection.uri)
                        assertNull(connection.performanceData)
                        assertTrue(coroutineContext[Job]!!.isActive)
                    }
                }
            }
        }

    @Test
    fun ipv6UriConnectsAndTransfersMessages() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair(host = "[::1]") { caller, listener ->
                    caller.send(byteArrayOf(6))
                    assertArrayEquals(byteArrayOf(6), listener.receive())
                }
            }
        }

    @Test
    fun rendezvousUrisBindLocalPortsAndConnect() =
        runBlocking {
            withTimeout(10_000) {
                val ports = DatagramSocket(0).use { first -> DatagramSocket(0).use { second -> first.localPort to second.localPort } }
                SrtConnection(this).use { first ->
                    SrtConnection(this).use { second ->
                        val pending =
                            async {
                                first.connect(URI("srt://127.0.0.1:${ports.second}?mode=rendezvous&adapter=127.0.0.1&port=${ports.first}"))
                            }
                        second.connect("srt://127.0.0.1:${ports.first}?mode=rendezvous&adapter=127.0.0.1&port=${ports.second}")
                        pending.await()
                        first.send(byteArrayOf(1, 2))
                        assertArrayEquals(byteArrayOf(1, 2), second.receive())
                    }
                }
            }
        }

    private suspend fun connectedPair(
        options: SrtConnection.Options = SrtConnection.Options(),
        host: String = "127.0.0.1",
        block: suspend (SrtConnection, SrtConnection) -> Unit,
    ) = coroutineScope {
        SrtConnection(this, options).use { listener ->
            SrtConnection(this, options).use { caller ->
                val accepting = async { listener.connect("srt://$host:0?mode=listener") }
                listener.readyState.first { it == SrtConnection.ReadyState.LISTENING }
                caller.connect("srt://$host:${listener.localAddress!!.port}")
                accepting.await()
                block(caller, listener)
            }
        }
    }
}
