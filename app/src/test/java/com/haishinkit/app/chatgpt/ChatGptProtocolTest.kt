package com.haishinkit.app.chatgpt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

class ChatGptProtocolTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = pair.public as RSAPublicKey
    private val jwks =
        buildJsonObject {
            put(
                "keys",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("kid", "test")
                            put("kty", "RSA")
                            put("n", ChatGptProtocol.encode(publicKey.modulus.toByteArray()))
                            put("e", ChatGptProtocol.encode(publicKey.publicExponent.toByteArray()))
                        },
                    ),
                ),
            )
        }
    private val claims =
        buildJsonObject {
            put("iss", ChatGptProtocol.ISSUER)
            put("sub", "account-123")
            put("aud", "issued-client")
            put("nonce", "nonce-123")
            put("iat", 900)
            put("exp", 1100)
        }

    @Test
    fun pkceUsesRfc7636VectorAndFreshRandomSecrets() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val first = ChatGptProtocol.randomValue()
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertTrue(first != ChatGptProtocol.randomValue())
    }

    @Test
    fun verifiesSignatureAndEveryIdentityBoundary() {
        assertEquals(claims, ChatGptProtocol.verifyIdentity(sign(claims), jwks, "issued-client", "nonce-123", 1000))
        for ((name, value) in mapOf("iss" to JsonPrimitive("https://example.com"), "aud" to JsonPrimitive("other-client"), "nonce" to JsonPrimitive("other-nonce"), "exp" to JsonPrimitive(999), "iat" to JsonPrimitive(1100), "sub" to JsonPrimitive(""))) {
            val invalid = JsonObject(claims + (name to value))
            assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.verifyIdentity(sign(invalid), jwks, "issued-client", "nonce-123", 1000) }
        }
    }

    @Test
    fun rejectsTamperedTokenAndMultiAudienceWithoutAuthorizedParty() {
        val token = sign(claims)
        val tampered = token.split('.').toMutableList().apply { set(1, ChatGptProtocol.encode(JsonObject(claims + ("sub" to JsonPrimitive("attacker"))).toString().toByteArray())) }.joinToString(".")
        assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.verifyIdentity(tampered, jwks, "issued-client", "nonce-123", 1000) }
        val multiAudience = JsonObject(claims + ("aud" to JsonArray(listOf(JsonPrimitive("issued-client"), JsonPrimitive("other")))))
        assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.verifyIdentity(sign(multiAudience), jwks, "issued-client", "nonce-123", 1000) }
        val authorized = JsonObject(multiAudience + ("azp" to JsonPrimitive("issued-client")))
        assertEquals(authorized, ChatGptProtocol.verifyIdentity(sign(authorized), jwks, "issued-client", "nonce-123", 1000))
    }

    @Test
    fun callbackRejectsWrongStateDuplicateParametersAndForeignHost() {
        val valid = "GET /auth/callback?code=one-time&state=expected&client_id=issued HTTP/1.1\r\nHost: 127.0.0.1:12345\r\n\r\n"
        assertEquals("one-time", ChatGptProtocol.callback(valid, 12345, "expected")["code"])
        for (invalid in listOf(
            valid.replace("state=expected", "state=other"),
            valid.replace("state=expected", "state=expected&state=expected"),
            valid.replace("127.0.0.1", "evil.example"),
            valid.replace("/auth/callback?", "http://evil.example/auth/callback?"),
            valid.replace("/auth/callback?", "/callback?"),
            valid.trimEnd(),
        )) {
            assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.callback(invalid, 12345, "expected") }
        }
    }

    private fun sign(payload: JsonObject): String {
        val header = ChatGptProtocol.encode("""{"alg":"RS256","kid":"test"}""".toByteArray())
        val body = ChatGptProtocol.encode(payload.toString().toByteArray())
        val signature =
            Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private)
                update("$header.$body".toByteArray())
                sign()
            }
        return "$header.$body.${ChatGptProtocol.encode(signature)}"
    }
}
