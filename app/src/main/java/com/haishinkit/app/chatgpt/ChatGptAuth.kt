package com.haishinkit.app.chatgpt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLEncoder

internal data class ChatGptAccount(val id: String, val label: String, val planEnabled: Boolean, val connected: Boolean = planEnabled)

/** OAuth public client: this app has its own registration, never a Codex application's client ID. */
internal class ChatGptAuth(context: Context, private val http: ChatGptAccountsHttp) {
    private val store = ChatGptStore(context)
    private val mutex = Mutex()
    private var state: JsonObject? = null

    suspend fun accounts(): List<ChatGptAccount> =
        mutex.withLock {
            initialize()
            profiles().map { profile ->
                val id = profile.text("client_id")
                ChatGptAccount(id, "${profile.textOrNull("email") ?: "ChatGPT"} · ${id.takeLast(6)}", profile.scopes().contains(ChatGptProtocol.PLAN_SCOPE), profile.textOrNull("access_token") != null)
            }
        }

    suspend fun needsPlanNotice(): Boolean =
        mutex.withLock {
            initialize()
            state!!["plan_notice_seen"] != JsonPrimitive(true)
        }

    suspend fun acknowledgePlanNotice(): Unit =
        mutex.withLock {
            initialize()
            val next = JsonObject(state!! + ("plan_notice_seen" to JsonPrimitive(true)))
            withContext(NonCancellable) {
                store.write(next)
                state = next
            }
        }

    suspend fun signIn(
        selectedId: String?,
        openBrowser: (String) -> Unit,
    ): String =
        mutex.withLock {
            initialize()
            val selected = profiles().firstOrNull { it.text("client_id") == selectedId }
            val verifier = ChatGptProtocol.randomValue()
            val nonce = ChatGptProtocol.randomValue()
            val expectedState = ChatGptProtocol.randomValue()
            val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1_000 }
            try {
                val redirect = "http://127.0.0.1:${listener.localPort}/auth/callback"
                val parameters =
                    linkedMapOf(
                        "client_id" to (selectedId ?: ChatGptProtocol.DYNAMIC_CLIENT),
                        "ext_agent_host_id" to state!!.text("host_id"),
                        "response_type" to "code", "redirect_uri" to redirect,
                        "scope" to ChatGptProtocol.SCOPES, "resource" to ChatGptProtocol.RESOURCE,
                        "state" to expectedState, "nonce" to nonce,
                        "code_challenge_method" to "S256", "code_challenge" to ChatGptProtocol.challenge(verifier),
                    )
                if (selectedId == null) parameters["agent_name_hint"] = "HaishinKit Android sample"
                selected?.textOrNull("id_token")?.let { parameters["id_token_hint"] = it }
                selected?.textOrNull("email")?.let { parameters["login_hint"] = it }
                withContext(Dispatchers.Main) { openBrowser("${ChatGptProtocol.ISSUER}/api/accounts/authorize?${encode(parameters)}") }
                val callback = withTimeout(180_000) { receiveCallback(listener, expectedState) }
                callback["error"]?.let { throw OpenAiFailure(if (it == "access_denied") "access_denied" else "sign_in_failed") }
                val issuedId =
                    if (selectedId == null) {
                        checkNotNull(callback["client_id"]?.takeIf { it.isNotBlank() && it != ChatGptProtocol.DYNAMIC_CLIENT }) { "Registration did not return a client ID" }
                    } else {
                        require(callback["client_id"] == null || callback["client_id"] == selectedId) { "Registration changed unexpectedly" }
                        selectedId
                    }
                // Keep a newly issued registration even if its one-time code later expires.
                if (profiles().none { it.text("client_id") == issuedId }) replaceProfile(buildJsonObject { put("client_id", issuedId) })
                val tokens =
                    http.form(
                        "${ChatGptProtocol.ISSUER}/api/accounts/oauth/token",
                        mapOf(
                            "grant_type" to "authorization_code",
                            "client_id" to issuedId,
                            "code" to checkNotNull(callback["code"]),
                            "code_verifier" to verifier,
                            "redirect_uri" to redirect,
                            "resource" to ChatGptProtocol.RESOURCE,
                        ),
                    )
                val metadata = http.get("${ChatGptProtocol.ISSUER}/.well-known/openid-configuration")
                require(metadata.text("issuer") == ChatGptProtocol.ISSUER)
                val claims = ChatGptProtocol.verifyIdentity(tokens.text("id_token"), http.get(metadata.text("jwks_uri")), issuedId, nonce)
                profiles().firstOrNull { it.text("client_id") == issuedId }?.textOrNull("subject")?.let { require(it == claims.text("sub")) { "The selected account changed" } }
                currentCoroutineContext().ensureActive()
                replaceProfile(
                    tokenRecord(
                        issuedId,
                        tokens,
                        buildJsonObject {
                            put("subject", claims.text("sub"))
                            claims["email"]?.let { put("email", it) }
                        },
                    ),
                )
                issuedId
            } finally {
                listener.close()
            }
        }

    suspend fun accessToken(clientId: String): String =
        mutex.withLock {
            initialize()
            var profile = profiles().single { it.text("client_id") == clientId }
            check(ChatGptProtocol.PLAN_SCOPE in profile.scopes()) { "ChatGPT plan usage was not authorized" }
            if ((profile["expires_at"]?.jsonPrimitive?.longOrNull ?: 0) <= System.currentTimeMillis() + 60_000) {
                val tokens =
                    http.form(
                        "${ChatGptProtocol.ISSUER}/api/accounts/oauth/token",
                        mapOf(
                            "grant_type" to "refresh_token",
                            "client_id" to clientId,
                            "refresh_token" to profile.text("refresh_token"),
                            "resource" to ChatGptProtocol.RESOURCE,
                        ),
                    )
                profile = tokenRecord(clientId, tokens, profile)
                replaceProfile(profile)
            }
            check(ChatGptProtocol.PLAN_SCOPE in profile.scopes())
            profile.text("access_token")
        }

    /** Returns false if local sign-out succeeded but server-side revocation was not confirmed. */
    suspend fun signOut(clientId: String): Boolean =
        mutex.withLock {
            initialize()
            val profile = profiles().single { it.text("client_id") == clientId }
            var revoked = true
            profile.textOrNull("refresh_token")?.let { token ->
                revoked =
                    runCatching {
                        val metadata = http.get("${ChatGptProtocol.ISSUER}/.well-known/openid-configuration")
                        http.form(metadata.text("revocation_endpoint"), mapOf("token" to token, "token_type_hint" to "refresh_token", "client_id" to clientId))
                    }.isSuccess
            }
            replaceProfile(
                buildJsonObject {
                    put("client_id", clientId)
                    profile["subject"]?.let { put("subject", it) }
                    profile["email"]?.let { put("email", it) }
                },
            )
            revoked
        }

    private suspend fun initialize() {
        if (state == null) {
            val initial = store.read()
            store.write(initial)
            state = initial
        }
    }

    private fun profiles(): List<JsonObject> = state!!["profiles"]!!.jsonArray.map { it.jsonObject }

    private suspend fun replaceProfile(profile: JsonObject) {
        val next =
            buildJsonObject {
                state!!.forEach { (key, value) -> put(key, value) }
                put("profiles", JsonArray(profiles().filterNot { it.text("client_id") == profile.text("client_id") } + profile))
            }
        withContext(NonCancellable) {
            store.write(next)
            state = next
        }
    }

    private fun tokenRecord(
        id: String,
        tokens: JsonObject,
        previous: JsonObject,
    ): JsonObject =
        buildJsonObject {
            require(tokens.text("token_type").equals("Bearer", ignoreCase = true))
            previous.forEach { (k, v) -> put(k, v) }
            put("client_id", id)
            listOf("id_token", "access_token", "refresh_token").forEach { name -> tokens[name]?.let { put(name, it) } }
            put("expires_at", System.currentTimeMillis() + tokens["expires_in"]!!.jsonPrimitive.long * 1000)
            tokens.textOrNull("scope")?.let { put("scopes", JsonArray(it.split(' ').filter { scope -> scope.isNotBlank() }.map(::JsonPrimitive))) }
        }

    private suspend fun receiveCallback(
        listener: ServerSocket,
        expectedState: String,
    ): Map<String, String> =
        withContext(Dispatchers.IO) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val socket =
                    try {
                        listener.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                socket.use {
                    it.soTimeout = 1_500
                    val callback =
                        runCatching {
                            val bytes = ByteArrayOutputStream()
                            while (bytes.size() < 8_192) {
                                val value = it.getInputStream().read()
                                if (value < 0) error("Incomplete callback")
                                bytes.write(value)
                                if (bytes.size() >= 4 && bytes.toString("US-ASCII").endsWith("\r\n\r\n")) break
                            }
                            ChatGptProtocol.callback(bytes.toString("US-ASCII"), listener.localPort, expectedState)
                        }.getOrNull()
                    val body = if (callback == null) "Invalid callback" else "Sign-in received. Return to HaishinKit."
                    it.getOutputStream().write("HTTP/1.1 ${if (callback == null) "400 Bad Request" else "200 OK"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
                    if (callback != null) return@withContext callback
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("No callback")
        }

    private fun encode(values: Map<String, String>): String = values.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
}

internal fun JsonObject.text(name: String): String = get(name)!!.jsonPrimitive.content

internal fun JsonObject.textOrNull(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.scopes(): Set<String> = get("scopes")?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
