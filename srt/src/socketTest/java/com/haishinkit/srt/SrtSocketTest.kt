package com.haishinkit.srt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** Runs against real libsrt, on an Android device or with a host JNI build. */
class SrtSocketTest {
    @Before
    fun requireNativeRuntime() {
        val android = System.getProperty("java.vm.name") == "Dalvik"
        val hostLibrary =
            System.getProperty("java.library.path").orEmpty().split(File.pathSeparator).any {
                File(it, System.mapLibraryName("srt")).isFile
            }
        assumeTrue("Run on Android or supply -PsrtNativeLibraryDir=<host JNI directory>", android || hostLibrary)
    }

    @Test
    fun encryptedLoopbackPreservesMessagesAndStatistics() =
        runBlocking {
            withTimeout(10_000) {
                val options = SrtSocket.Options(passphrase = "socket-test-secret", streamId = "test-stream")
                connectedPair(options) { sender, receiver ->
                    val data = ByteArray(SrtSocket.PAYLOAD_SIZE * 2 + 17) { (it % 251).toByte() }
                    sender.send(data)
                    val messages = List(3) { receiver.receive() }
                    assertEquals(listOf(1316, 1316, 17), messages.map { it.size })
                    assertArrayEquals(data, messages.fold(byteArrayOf()) { result, next -> result + next })
                    assertTrue(sender.performanceData().bytesSent >= data.size)
                    val first = receiver.performanceData()
                    assertTrue(first.bytesReceived >= data.size)
                    assertTrue(first.pktRecv > 0)
                    val second = receiver.performanceData()
                    assertTrue(second.bytesReceived >= first.bytesReceived)
                    assertTrue(second.pktRecv >= first.pktRecv)
                }
            }
        }

    @Test
    fun cancellingReceiveClosesSocketWithoutShuttingDownRuntime() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { _, receiver ->
                    val pending = launch(start = CoroutineStart.UNDISPATCHED) { receiver.receive() }
                    pending.cancelAndJoin()
                    assertEquals(SrtSocket.Status.CLOSED, receiver.status)
                    // The original peer may already observe the disconnect. Verify
                    // global runtime health with an independent connection instead.
                    connectedPair { sender, nextReceiver ->
                        sender.send(byteArrayOf(42))
                        assertArrayEquals(byteArrayOf(42), nextReceiver.receive())
                    }
                }
            }
        }

    @Test
    fun closingSocketInterruptsReceive() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { _, receiver ->
                    val pending =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            runCatching { receiver.receive() }
                        }
                    receiver.close()
                    assertTrue(pending.await().exceptionOrNull() is IllegalStateException)
                    receiver.close()
                    assertEquals(SrtSocket.Status.CLOSED, receiver.status)
                }
            }
        }

    @Test
    fun cancellingAcceptClosesListener() =
        runBlocking {
            withTimeout(5000) {
                SrtSocket().use { listener ->
                    listener.listen(loopback(0))
                    val pending = launch(start = CoroutineStart.UNDISPATCHED) { listener.accept().close() }
                    pending.cancelAndJoin()
                    assertEquals(SrtSocket.Status.CLOSED, listener.status)
                }
            }
        }

    @Test
    fun cancellingConnectClosesSocket() =
        runBlocking {
            withTimeout(5000) {
                DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { unavailable ->
                    SrtSocket(SrtSocket.Options(connectTimeoutMs = 10_000)).use { socket ->
                        val pending =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                socket.connect(loopback(unavailable.localPort))
                            }
                        pending.cancelAndJoin()
                        assertEquals(SrtSocket.Status.CLOSED, socket.status)
                    }
                }
            }
        }

    @Test
    fun connectTimeoutClosesSocket() =
        runBlocking {
            withTimeout(5000) {
                DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { unavailable ->
                    SrtSocket(SrtSocket.Options(connectTimeoutMs = 200)).use { socket ->
                        val result = runCatching { socket.connect(loopback(unavailable.localPort)) }
                        assertTrue(result.isFailure)
                        assertEquals(SrtSocket.Status.CLOSED, socket.status)
                    }
                }
            }
        }

    @Test
    fun nativeErrorsRetainErrorCode() {
        SrtSocket().use { first ->
            first.listen(loopback(0))
            SrtSocket().use { second ->
                val failure = runCatching { second.listen(first.localAddress) }.exceptionOrNull()
                assertTrue(failure is SrtSocketException)
                assertTrue((failure as SrtSocketException).errorCode != 0)
                assertEquals(SrtSocket.Status.CLOSED, second.status)
            }
        }
    }

    @Test
    fun mismatchedPassphrasesRejectConnection() =
        runBlocking {
            withTimeout(10_000) {
                SrtSocket(SrtSocket.Options(passphrase = "listener-secret")).use { listener ->
                    listener.listen(loopback(0))
                    SrtSocket(SrtSocket.Options(passphrase = "different-secret")).use { caller ->
                        val error = runCatching { caller.connect(listener.localAddress) }.exceptionOrNull()
                        assertTrue("Expected native rejection, got $error", error is SrtSocketException)
                        assertTrue((error as SrtSocketException).rejectReason > 0)
                        assertEquals(SrtSocket.Status.CLOSED, caller.status)
                    }
                }
            }
        }

    @Test
    fun cancellingBackpressuredSendClosesSocket() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { sender, _ ->
                    val pending =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            sender.send(ByteArray(16 * 1024 * 1024))
                        }
                    assertTrue("Send should suspend when the native buffer fills", pending.isActive)
                    pending.cancelAndJoin()
                    assertEquals(SrtSocket.Status.CLOSED, sender.status)
                }
            }
        }

    @Test
    fun cancellingPacketAlignedStreamSendKeepsTransportOpen() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { sender, receiver ->
                    val pending =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            sender.send(ByteArray(188 * 100_000), closeOnCancellation = false)
                        }
                    assertTrue(pending.isActive)
                    pending.cancelAndJoin()
                    assertEquals(SrtSocket.Status.CONNECTED, sender.status)
                    assertEquals(0, receiver.receive().size % 188)
                    val next =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            sender.send(byteArrayOf(42))
                        }
                    while (!receiver.receive().contentEquals(byteArrayOf(42))) { }
                    next.join()
                }
            }
        }

    @Test
    fun nativeSendPreservesChunksAcrossMultipleBursts() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { sender, receiver ->
                    val data = ByteArray(1316 * 33 + 17) { (it % 251).toByte() }
                    sender.send(data)
                    val chunks = List(34) { receiver.receive() }
                    assertTrue(chunks.take(33).all { it.size == 1316 })
                    assertEquals(17, chunks.last().size)
                    assertArrayEquals(data, chunks.fold(byteArrayOf()) { all, next -> all + next })
                }
            }
        }

    @Test
    fun nativeContextSurvivesConcurrentCallsAndClose() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { _, receiver ->
                    coroutineScope {
                        val start = CompletableDeferred<Unit>()
                        val readers =
                            List(4) {
                                launch(Dispatchers.Default) {
                                    start.await()
                                    repeat(200) {
                                        receiver.status
                                        val error = runCatching { receiver.performanceData() }.exceptionOrNull()
                                        assertTrue(error == null || error is IllegalStateException || error is SrtSocketException)
                                    }
                                }
                            }
                        val closers =
                            List(4) {
                                launch(Dispatchers.Default) {
                                    start.await()
                                    receiver.close()
                                }
                            }
                        start.complete(Unit)
                        (readers + closers).forEach { it.join() }
                    }
                    assertEquals(SrtSocket.Status.CLOSED, receiver.status)
                    SrtSocket().use { replacement ->
                        receiver.close()
                        assertEquals(SrtSocket.Status.INIT, replacement.status)
                    }
                }
            }
        }

    @Test
    fun invalidOptionsAreRejectedByNativeImplementation() {
        val invalid =
            listOf(
                SrtSocket.Options(latencyMs = -1),
                SrtSocket.Options(connectTimeoutMs = 0),
                SrtSocket.Options(passphrase = "short"),
                SrtSocket.Options(streamId = "a".repeat(513)),
            )
        invalid.forEach { options ->
            val error = runCatching { SrtSocket(options).close() }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
    }

    @Test
    fun concurrentReceiveIsRejectedWithoutClosingTheActiveReader() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { sender, receiver ->
                    val pending = async(start = CoroutineStart.UNDISPATCHED) { receiver.receive() }
                    assertTrue(runCatching { receiver.receive() }.exceptionOrNull() is IllegalStateException)
                    assertEquals(SrtSocket.Status.CONNECTED, receiver.status)
                    sender.send(byteArrayOf(7))
                    assertArrayEquals(byteArrayOf(7), pending.await())
                }
            }
        }

    @Test
    fun inputCollectionOwnsReaderWhileCollectorIsSuspended() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { sender, receiver ->
                    val received = CompletableDeferred<Unit>()
                    val collection =
                        launch {
                            receiver.inputs.collect {
                                received.complete(Unit)
                                awaitCancellation()
                            }
                        }
                    sender.send(byteArrayOf(1))
                    received.await()
                    assertTrue(runCatching { receiver.receive() }.exceptionOrNull() is IllegalStateException)
                    assertTrue(runCatching { receiver.inputs.take(1).toList() }.exceptionOrNull() is IllegalStateException)
                    assertEquals(SrtSocket.Status.CONNECTED, receiver.status)
                    collection.cancelAndJoin()
                    assertEquals(SrtSocket.Status.CLOSED, receiver.status)
                }
            }
        }

    @Test
    fun concurrentAcceptIsRejectedWithoutClosingListener() =
        runBlocking {
            withTimeout(10_000) {
                SrtSocket().use { listener ->
                    listener.listen(loopback(0))
                    val pending = async(start = CoroutineStart.UNDISPATCHED) { listener.accept() }
                    assertTrue(runCatching { listener.accept() }.exceptionOrNull() is IllegalStateException)
                    assertEquals(SrtSocket.Status.LISTENING, listener.status)
                    SrtSocket().use { caller ->
                        caller.connect(listener.localAddress)
                        pending.await().use { assertEquals(SrtSocket.Status.CONNECTED, it.status) }
                    }
                }
            }
        }

    @Test
    fun concurrentConnectIsRejectedWithoutCancellingOriginalAttempt() =
        runBlocking {
            withTimeout(5000) {
                DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { unavailable ->
                    SrtSocket(SrtSocket.Options(connectTimeoutMs = 10_000)).use { socket ->
                        val remote = loopback(unavailable.localPort)
                        val pending = launch(start = CoroutineStart.UNDISPATCHED) { socket.connect(remote) }
                        assertTrue(runCatching { socket.connect(remote) }.exceptionOrNull() is IllegalStateException)
                        assertEquals(SrtSocket.Status.CONNECTING, socket.status)
                        pending.cancelAndJoin()
                        assertEquals(SrtSocket.Status.CLOSED, socket.status)
                    }
                }
            }
        }

    @Test
    fun rendezvousConnectsAndTransfersData() =
        runBlocking {
            withTimeout(10_000) {
                val ports =
                    DatagramSocket(0).use { first ->
                        DatagramSocket(0).use { second -> first.localPort to second.localPort }
                    }
                SrtSocket().use { first ->
                    SrtSocket().use { second ->
                        coroutineScope {
                            val connectFirst = async { first.connect(loopback(ports.second), loopback(ports.first), rendezvous = true) }
                            second.connect(loopback(ports.first), loopback(ports.second), rendezvous = true)
                            connectFirst.await()
                        }
                        first.send(byteArrayOf(1, 2, 3))
                        assertArrayEquals(byteArrayOf(1, 2, 3), second.receive())
                    }
                }
            }
        }

    @Test
    fun peerDisconnectIsReportedAndClosesReceiver() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { sender, receiver ->
                    val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { receiver.receive() } }
                    sender.close()
                    assertTrue(pending.await().exceptionOrNull() is SrtSocketException)
                    assertEquals(SrtSocket.Status.CLOSED, receiver.status)
                }
            }
        }

    @Test
    fun completingInputCollectionClosesReceiver() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { sender, receiver ->
                    sender.send(byteArrayOf(42))
                    assertArrayEquals(byteArrayOf(42), receiver.inputs.take(1).toList().single())
                    assertEquals(SrtSocket.Status.CLOSED, receiver.status)
                }
            }
        }

    @Test
    fun ipv6LoopbackTransfersData() =
        runBlocking {
            withTimeout(10_000) {
                SrtSocket().use { listener ->
                    listener.listen(InetSocketAddress(InetAddress.getByName("::1"), 0))
                    SrtSocket().use { caller ->
                        caller.connect(listener.localAddress)
                        listener.accept().use { receiver ->
                            caller.send(byteArrayOf(6))
                            assertArrayEquals(byteArrayOf(6), receiver.receive())
                        }
                    }
                }
            }
        }

    @Test
    fun concurrentSendsDoNotInterleaveChunks() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { sender, receiver ->
                    coroutineScope {
                        val first = async { sender.send(ByteArray(2632) { 1 }) }
                        val second = async { sender.send(ByteArray(2632) { 2 }) }
                        first.await()
                        second.await()
                    }
                    val chunks = List(4) { receiver.receive() }
                    assertArrayEquals(chunks[0], chunks[1])
                    assertArrayEquals(chunks[2], chunks[3])
                    assertTrue(chunks[0][0] != chunks[2][0])
                }
            }
        }

    @Test
    fun reconnectingAnOpenSocketDoesNotCloseIt() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { sender, receiver ->
                    assertTrue(runCatching { sender.connect(receiver.localAddress) }.exceptionOrNull() is IllegalStateException)
                    assertEquals(SrtSocket.Status.CONNECTED, sender.status)
                }
            }
        }

    @Test
    fun cancellingQueuedSenderDoesNotCloseActiveSender() =
        runBlocking {
            withTimeout(10_000) {
                connectedPair { sender, _ ->
                    val active = launch(start = CoroutineStart.UNDISPATCHED) { sender.send(ByteArray(16 * 1024 * 1024)) }
                    assertTrue(active.isActive)
                    val queued = launch(start = CoroutineStart.UNDISPATCHED) { sender.send(byteArrayOf(1)) }
                    queued.cancelAndJoin()
                    assertTrue(active.isActive)
                    assertEquals(SrtSocket.Status.CONNECTED, sender.status)
                    active.cancelAndJoin()
                    assertEquals(SrtSocket.Status.CLOSED, sender.status)
                }
            }
        }

    @Test
    fun sharedEventLoopSupportsConcurrentConnections() =
        runBlocking<Unit> {
            withTimeout(10_000) {
                repeat(8) {
                    coroutineScope {
                        List(8) { connection ->
                            async(Dispatchers.Default) {
                                connectedPair(SrtSocket.Options(latencyMs = 0)) { sender, receiver ->
                                    repeat(4) { message ->
                                        val payload = byteArrayOf(connection.toByte(), message.toByte())
                                        sender.send(payload)
                                        assertArrayEquals(payload, receiver.receive())
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
        }

    private suspend fun connectedPair(
        options: SrtSocket.Options = SrtSocket.Options(),
        block: suspend (SrtSocket, SrtSocket) -> Unit,
    ) = coroutineScope {
        SrtSocket(options).use { listener ->
            listener.listen(loopback(0))
            assertTrue(listener.localAddress.port > 0)
            SrtSocket(options).use { caller ->
                val accepted = async { listener.accept() }
                caller.connect(listener.localAddress)
                accepted.await().use { receiver ->
                    assertEquals(SrtSocket.Status.CONNECTED, caller.status)
                    assertEquals(SrtSocket.Status.CONNECTED, receiver.status)
                    block(caller, receiver)
                }
            }
        }
    }

    private fun loopback(port: Int) = InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
}
