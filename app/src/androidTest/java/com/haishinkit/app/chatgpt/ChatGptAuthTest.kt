package com.haishinkit.app.chatgpt

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
        private var nonce = ""
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

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
            } else {
                assertTrue(values["code_verifier"]!!.length >= 43)
                assertTrue(values["redirect_uri"]!!.startsWith("http://127.0.0.1:"))
            }
            return buildJsonObject {
                put("token_type", "Bearer")
                put("access_token", if (refresh) "refreshed-access" else "original-access")
                put("refresh_token", if (refresh) "rotated-refresh" else "original-refresh")
                put("expires_in", if (refresh) 3600 else 0)
                if (!refresh) {
                    put("scope", ChatGptProtocol.SCOPES)
                    put("id_token", identity())
                }
            }
        }

        fun browser(url: String) {
            val parameters = URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            nonce = parameters.getValue("nonce")
            lastAuthorizeClient = parameters.getValue("client_id")
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
