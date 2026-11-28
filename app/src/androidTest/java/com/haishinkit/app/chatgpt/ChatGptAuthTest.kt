package com.haishinkit.app.chatgpt

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.UUID

/** Fake OpenAI endpoints plus a real loopback callback and AndroidKeyStore; no real account/network. */
class ChatGptAuthTest {
    @Test
    fun registrationRefreshAndFailedRemoteSignOutPreserveSafeLocalState() =
        runBlocking {
            val context = isolatedContext()
            try {
                withTimeout(15_000) {
                    val http = FakeAccountsHttp()
                    val auth = ChatGptAuth(context, http)
                    assertTrue(auth.accounts().isEmpty())
                    val id = auth.signIn(null, http::browser)
                    assertEquals("issued-test-client", id)
                    assertTrue(auth.accounts().single().planEnabled)
                    assertTrue(auth.needsPlanNotice())
                    auth.acknowledgePlanNotice()
                    assertFalse(auth.needsPlanNotice())
                    assertEquals("refreshed-access", auth.accessToken(id))
                    assertEquals("refreshed-access", auth.accessToken(id))
                    assertEquals(1, http.refreshes)
                    val restored = ChatGptAuth(context, http)
                    assertEquals("refreshed-access", restored.accessToken(id))
                    assertFalse(restored.needsPlanNotice())
                    http.subject = "different-subject"
                    var rejectedIdentity = false
                    try {
                        restored.signIn(id, http::browser)
                    } catch (_: IllegalArgumentException) {
                        rejectedIdentity = true
                    }
                    assertTrue(rejectedIdentity)
                    assertEquals("refreshed-access", restored.accessToken(id))
                    val encrypted = File(context.noBackupFilesDir, "chatgpt-credentials-v1.bin").readBytes().toString(Charsets.ISO_8859_1)
                    assertFalse(encrypted.contains("refreshed-access"))
                    assertFalse(encrypted.contains("rotated-refresh"))
                    assertFalse(restored.signOut(id))
                    assertEquals("rotated-refresh", http.revokedToken)
                    assertFalse(restored.accounts().single().planEnabled)
                    assertEquals(id, restored.accounts().single().id)
                }
            } finally {
                context.noBackupFilesDir.deleteRecursively()
            }
        }

    @Test
    fun failedCodeExchangeRetainsIssuedRegistrationForRetry() =
        runBlocking {
            val context = isolatedContext()
            try {
                withTimeout(15_000) {
                    val http = FakeAccountsHttp().apply { failExchange = true }
                    val auth = ChatGptAuth(context, http)
                    var failed = false
                    try {
                        auth.signIn(null, http::browser)
                    } catch (_: OpenAiFailure) {
                        failed = true
                    }
                    assertTrue(failed)
                    val saved = auth.accounts().single()
                    assertEquals("issued-test-client", saved.id)
                    assertFalse(saved.planEnabled)
                    http.failExchange = false
                    auth.signIn(saved.id, http::browser)
                    assertEquals("issued-test-client", http.lastAuthorizeClient)
                    assertTrue(auth.accounts().single().planEnabled)
                }
            } finally {
                context.noBackupFilesDir.deleteRecursively()
            }
        }

    @Test
    fun missingPlanPermissionRequestsConsentOnlyForExplicitEnablement() =
        runBlocking {
            val context = isolatedContext()
            try {
                withTimeout(15_000) {
                    val http = FakeAccountsHttp().apply { grantedScopes = "openid profile email" }
                    val auth = ChatGptAuth(context, http)
                    val id = auth.signIn(null, http::browser)
                    assertFalse(auth.accounts().single().planEnabled)
                    assertEquals(null, http.lastPrompt)
                    auth.signOut(id)
                    assertFalse(auth.accounts().single().connected)
                    http.grantedScopes = ChatGptProtocol.SCOPES
                    auth.signIn(id, http::browser)
                    assertEquals("consent", http.lastPrompt)
                    assertTrue(auth.accounts().single().planEnabled)
                    auth.signIn(id, http::browser)
                    assertEquals(null, http.lastPrompt)
                }
            } finally {
                context.noBackupFilesDir.deleteRecursively()
            }
        }

    @Test
    fun canceledRenewalCompletesBeforeHttpShutdownAndNeverStartsInference() =
        runBlocking {
            for (terminal in listOf(false, true)) {
                val context = isolatedContext()
                try {
                    withTimeout(20_000) {
                        val entered = CompletableDeferred<Unit>()
                        val release = CompletableDeferred<Unit>()
                        val http =
                            FakeAccountsHttp().apply {
                                refreshEntered = entered
                                refreshRelease = release
                                if (terminal) refreshFailure = OpenAiFailure("invalid_grant", 400)
                            }
                        val auth = ChatGptAuth(context, http)
                        val id = auth.signIn(null, http::browser)
                        var inferenceStarted = false
                        val request =
                            launch {
                                auth.accessToken(id)
                                inferenceStarted = true
                            }
                        entered.await()
                        request.cancel()
                        val clearedOwner = CoroutineScope(Job().apply { cancel() } + Dispatchers.Main)
                        val shutdown = clearedOwner.launch(start = CoroutineStart.UNDISPATCHED) { auth.close() }
                        yield()
                        assertFalse(http.closed)
                        release.complete(Unit)
                        request.join()
                        shutdown.join()
                        assertFalse(inferenceStarted)
                        assertTrue(http.closed)
                        val replacementHttp = FakeAccountsHttp()
                        val restored = ChatGptAuth(context, replacementHttp)
                        if (terminal) {
                            val accounts = restored.accounts()
                            assertFalse(accounts.single().connected)
                            val previous = AiChatState(accounts = listOf(ChatGptAccount(id, "Saved account", true)), account = id, models = listOf(ChatModel("model", "Model")), model = "model")
                            val synchronized = previous.withAccounts(accounts, id, welcome = false, reconnectNotice = "Reconnect")
                            assertFalse(synchronized.accounts.single().planEnabled)
                            assertEquals(null, synchronized.model)
                            assertEquals("Reconnect", synchronized.notice)
                        } else {
                            assertEquals("refreshed-access", restored.accessToken(id))
                        }
                        assertEquals(0, replacementHttp.refreshes)
                        restored.close()
                    }
                } finally {
                    context.noBackupFilesDir.deleteRecursively()
                }
            }
        }

    @Test
    fun terminalRefreshErrorsClearTokensButRetainTheRegistration() =
        runBlocking {
            for (code in listOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")) {
                val context = isolatedContext()
                try {
                    withTimeout(15_000) {
                        val http = FakeAccountsHttp().apply { refreshFailure = OpenAiFailure(code, 400) }
                        val auth = ChatGptAuth(context, http)
                        val id = auth.signIn(null, http::browser)
                        val failure = runCatching { auth.accessToken(id) }.exceptionOrNull() as OpenAiFailure
                        assertEquals(code, failure.code)
                        assertTrue(failure.requiresSignIn)
                        val account = auth.accounts().single()
                        assertEquals(id, account.id)
                        assertFalse(account.connected)
                        assertFalse(account.planEnabled)
                        assertTrue(runCatching { auth.accessToken(id) }.isFailure)
                        assertEquals(1, http.refreshes)
                        auth.close()
                    }
                } finally {
                    context.noBackupFilesDir.deleteRecursively()
                }
            }
        }

    @Test
    fun temporaryRefreshFailuresKeepTheRenewableSession() =
        runBlocking {
            for (error in listOf(IOException("offline"), OpenAiFailure("temporarily_unavailable", 503))) {
                val context = isolatedContext()
                try {
                    withTimeout(15_000) {
                        val http = FakeAccountsHttp().apply { refreshFailure = error }
                        val auth = ChatGptAuth(context, http)
                        val id = auth.signIn(null, http::browser)
                        assertTrue(runCatching { auth.accessToken(id) }.isFailure)
                        assertTrue(auth.accounts().single().connected)
                        assertTrue(auth.accounts().single().planEnabled)
                        http.refreshFailure = null
                        assertEquals("refreshed-access", auth.accessToken(id))
                        assertEquals(2, http.refreshes)
                        auth.close()
                    }
                } finally {
                    context.noBackupFilesDir.deleteRecursively()
                }
            }
        }

    @Test
    fun stalledRefreshHasAFifteenSecondNetworkDeadline() =
        runBlocking {
            val context = isolatedContext()
            try {
                withTimeout(22_000) {
                    val http = FakeAccountsHttp().apply { refreshRelease = CompletableDeferred() }
                    val auth = ChatGptAuth(context, http)
                    val id = auth.signIn(null, http::browser)
                    val start = android.os.SystemClock.elapsedRealtime()
                    assertEquals("refresh_timeout", (runCatching { auth.accessToken(id) }.exceptionOrNull() as OpenAiFailure).code)
                    assertTrue(android.os.SystemClock.elapsedRealtime() - start < 20_000)
                    assertTrue(auth.accounts().single().connected)
                    auth.close()
                    assertTrue(http.closed)
                }
            } finally {
                context.noBackupFilesDir.deleteRecursively()
            }
        }

    @Test
    fun newOwnerWaitsForAnOlderOwnersRenewalAndDoesNotOverwriteIt() =
        runBlocking {
            val context = isolatedContext()
            try {
                withTimeout(20_000) {
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val firstHttp =
                        FakeAccountsHttp().apply {
                            refreshEntered = entered
                            refreshRelease = release
                        }
                    val oldOwner = ChatGptAuth(context, firstHttp)
                    val id = oldOwner.signIn(null, firstHttp::browser)
                    val newHttp = FakeAccountsHttp()
                    val newOwner = ChatGptAuth(context, newHttp)
                    assertTrue(newOwner.accounts().single().connected)
                    val oldRequest = launch { oldOwner.accessToken(id) }
                    entered.await()
                    oldRequest.cancel()
                    val resumed =
                        async {
                            newOwner.acknowledgePlanNotice()
                            newOwner.accessToken(id)
                        }
                    val shutdown = launch { oldOwner.close() }
                    yield()
                    assertFalse(resumed.isCompleted)
                    assertEquals(0, newHttp.refreshes)
                    release.complete(Unit)
                    assertEquals("refreshed-access", resumed.await())
                    oldRequest.join()
                    shutdown.join()
                    assertEquals(1, firstHttp.refreshes + newHttp.refreshes)
                    val restoredHttp = FakeAccountsHttp()
                    val restored = ChatGptAuth(context, restoredHttp)
                    assertEquals("refreshed-access", restored.accessToken(id))
                    assertFalse(restored.needsPlanNotice())
                    assertEquals(0, restoredHttp.refreshes)
                    newOwner.close()
                    restored.close()
                }
            } finally {
                context.noBackupFilesDir.deleteRecursively()
            }
        }

    @Test
    fun readingAValidSessionNeverReencryptsItsCredentialFile() =
        runBlocking {
            val context = isolatedContext()
            try {
                withTimeout(15_000) {
                    val http = FakeAccountsHttp().apply { initialExpiresIn = 3600 }
                    val auth = ChatGptAuth(context, http)
                    // Initial creation must persist the stable host even before first authorization.
                    auth.accounts()
                    val file = File(context.noBackupFilesDir, "chatgpt-credentials-v1.bin")
                    assertTrue(file.isFile)
                    val id = auth.signIn(null, http::browser)
                    val before = file.readBytes()
                    repeat(3) {
                        assertTrue(auth.accounts().single().connected)
                        auth.needsPlanNotice()
                        assertEquals("original-access", auth.accessToken(id))
                    }
                    assertArrayEquals(before, file.readBytes())
                    assertEquals(0, http.refreshes)
                    auth.close()
                }
            } finally {
                context.noBackupFilesDir.deleteRecursively()
            }
        }

    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "chatgpt-auth-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    private class FakeAccountsHttp : ChatGptAccountsHttp {
        var refreshes = 0
        var revokedToken: String? = null
        var failExchange = false
        var subject = "fake-subject"
        var lastAuthorizeClient: String? = null
        var lastPrompt: String? = null
        var grantedScopes = ChatGptProtocol.SCOPES
        var initialExpiresIn = 0
        var refreshFailure: Exception? = null
        var refreshEntered: CompletableDeferred<Unit>? = null
        var refreshRelease: CompletableDeferred<Unit>? = null
        var closed = false
        private var nonce = ""
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        override fun close() {
            closed = true
        }

        override suspend fun get(
            url: String,
            token: String?,
        ): JsonObject =
            if (url.endsWith("openid-configuration")) {
                buildJsonObject {
                    put("issuer", ChatGptProtocol.ISSUER)
                    put("jwks_uri", "https://auth.openai.com/test-jwks")
                    put("revocation_endpoint", "https://auth.openai.com/test-revoke")
                }
            } else {
                val key = pair.public as RSAPublicKey
                buildJsonObject {
                    put(
                        "keys",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("kid", "test")
                                    put("kty", "RSA")
                                    put("n", ChatGptProtocol.encode(key.modulus.toByteArray()))
                                    put("e", ChatGptProtocol.encode(key.publicExponent.toByteArray()))
                                },
                            ),
                        ),
                    )
                }
            }

        override suspend fun form(
            url: String,
            values: Map<String, String>,
        ): JsonObject {
            check(!closed)
            assertEquals("issued-test-client", values["client_id"])
            if (url.endsWith("test-revoke")) {
                revokedToken = values["token"]
                throw OpenAiFailure("offline")
            }
            if (failExchange) throw OpenAiFailure("expired_code")
            val refresh = values["grant_type"] == "refresh_token"
            if (refresh) {
                refreshes++
                assertEquals("original-refresh", values["refresh_token"])
                refreshEntered?.complete(Unit)
                refreshRelease?.await()
                refreshFailure?.let { throw it }
            } else {
                assertTrue(values["code_verifier"]!!.length >= 43)
                assertTrue(values["redirect_uri"]!!.startsWith("http://127.0.0.1:"))
            }
            return buildJsonObject {
                put("token_type", "Bearer")
                put("access_token", if (refresh) "refreshed-access" else "original-access")
                put("refresh_token", if (refresh) "rotated-refresh" else "original-refresh")
                put("expires_in", if (refresh) 3600 else initialExpiresIn)
                if (!refresh) {
                    put("scope", grantedScopes)
                    put("id_token", identity())
                }
            }
        }

        fun browser(url: String) {
            val parameters = URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            nonce = parameters.getValue("nonce")
            lastAuthorizeClient = parameters.getValue("client_id")
            lastPrompt = parameters["prompt"]
            val redirect = URI(parameters.getValue("redirect_uri"))
            val state = URLEncoder.encode(parameters.getValue("state"), "UTF-8")
            Thread {
                Socket("127.0.0.1", redirect.port).use { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().write("GET /auth/callback?code=fake-code&client_id=issued-test-client&state=$state HTTP/1.1\r\nHost: 127.0.0.1:${redirect.port}\r\n\r\n".toByteArray())
                    socket.getInputStream().readBytes()
                }
            }.start()
        }

        private fun identity(): String {
            val header = ChatGptProtocol.encode("""{"alg":"RS256","kid":"test"}""".toByteArray())
            val body =
                ChatGptProtocol.encode(
                    buildJsonObject {
                        put("iss", ChatGptProtocol.ISSUER)
                        put("aud", "issued-test-client")
                        put("sub", subject)
                        put("nonce", nonce)
                        put("iat", System.currentTimeMillis() / 1000)
                        put("exp", System.currentTimeMillis() / 1000 + 3600)
                    }.toString().toByteArray(),
                )
            val signature =
                Signature.getInstance("SHA256withRSA").run {
                    initSign(pair.private)
                    update("$header.$body".toByteArray())
                    sign()
                }
            return "$header.$body.${ChatGptProtocol.encode(signature)}"
        }
    }
}
