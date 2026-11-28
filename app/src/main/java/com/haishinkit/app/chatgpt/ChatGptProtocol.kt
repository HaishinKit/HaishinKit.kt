package com.haishinkit.app.chatgpt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

internal object ChatGptProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DYNAMIC_CLIENT = "dynamic_agent_client"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"

    fun randomValue(): String = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun challenge(verifier: String): String = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun verifyIdentity(
        token: String,
        jwks: JsonObject,
        clientId: String,
        nonce: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): JsonObject {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid identity token" }
        val header = Json.parseToJsonElement(String(decode(parts[0]), Charsets.UTF_8)).jsonObject
        require(header["alg"]?.jsonPrimitive?.content == "RS256" && header["crit"] == null) { "Unsupported identity signature" }
        val kid = header["kid"]?.jsonPrimitive?.content
        val key =
            jwks["keys"]!!.jsonArray.map { it.jsonObject }.single {
                it["kid"]?.jsonPrimitive?.content == kid && it["kty"]?.jsonPrimitive?.content == "RSA" &&
                    (it["use"] == null || it["use"]?.jsonPrimitive?.content == "sig") &&
                    (it["alg"] == null || it["alg"]?.jsonPrimitive?.content == "RS256")
            }
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, decode(key["n"]!!.jsonPrimitive.content)), BigInteger(1, decode(key["e"]!!.jsonPrimitive.content))))
        val valid =
            Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(decode(parts[2]))
            }
        require(valid) { "Invalid identity signature" }
        val claims = Json.parseToJsonElement(String(decode(parts[1]), Charsets.UTF_8)).jsonObject
        require(claims["iss"]?.jsonPrimitive?.content == ISSUER) { "Invalid identity issuer" }
        val audience = claims["aud"]
        val audiences = if (audience is JsonArray) audience.map { it.jsonPrimitive.content } else listOfNotNull((audience as? JsonPrimitive)?.content)
        require(clientId in audiences) { "Invalid identity audience" }
        val authorizedParty = claims["azp"]?.jsonPrimitive?.content
        require((audiences.size == 1 && authorizedParty == null) || authorizedParty == clientId) { "Invalid authorized party" }
        require(claims["exp"]!!.jsonPrimitive.long > nowSeconds) { "Expired identity token" }
        require((claims["nbf"]?.jsonPrimitive?.longOrNull ?: 0) <= nowSeconds + 60) { "Identity token is not active" }
        require(claims["iat"]!!.jsonPrimitive.long <= nowSeconds + 60) { "Invalid identity issue time" }
        require(equal(claims["nonce"]?.jsonPrimitive?.content.orEmpty(), nonce)) { "Invalid identity nonce" }
        require(!claims["sub"]?.jsonPrimitive?.content.isNullOrBlank()) { "Missing account identity" }
        return claims
    }

    fun callback(
        headers: String,
        port: Int,
        expectedState: String,
    ): Map<String, String> {
        require(headers.endsWith("\r\n\r\n")) { "Incomplete callback headers" }
        val lines = headers.split("\r\n")
        val request = lines.first().split(' ')
        require(request.size == 3 && request[0] == "GET" && request[2] == "HTTP/1.1")
        val hosts = lines.drop(1).filter { it.substringBefore(':').equals("Host", true) }
        require(hosts.size == 1 && hosts.single().substringAfter(':').trim() == "127.0.0.1:$port")
        val uri = java.net.URI(request[1])
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawFragment == null && uri.rawPath == "/auth/callback")
        val pairs =
            uri.rawQuery.orEmpty().split('&').map { part ->
                java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
        require(pairs.map { it.first }.distinct().size == pairs.size)
        return pairs.toMap().also { require(equal(it["state"].orEmpty(), expectedState)) }
    }

    fun equal(
        first: String,
        second: String,
    ): Boolean = MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
