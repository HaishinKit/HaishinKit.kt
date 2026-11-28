package com.haishinkit.app.chatgpt

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class OpenAiFailure(val code: String, val status: Int = 0, val requiresSignIn: Boolean = false, val inferenceRestricted: Boolean = false) : IOException("OpenAI request failed ($status, $code)")

/** Credentials are only sent to the pinned OpenAI HTTPS endpoints, never to MCP. */
internal fun interface ChatGptInference {
    suspend fun responses(
        token: String,
        body: JsonObject,
        onDelta: (String) -> Unit,
    ): JsonObject
}

internal interface ChatGptAccountsHttp {
    fun close()

    suspend fun get(
        url: String,
        token: String? = null,
    ): JsonObject

    suspend fun form(
        url: String,
        values: Map<String, String>,
    ): JsonObject
}

internal class OpenAiHttp : ChatGptInference, ChatGptAccountsHttp {
    private val client =
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS).build()

    override suspend fun get(
        url: String,
        token: String?,
    ): JsonObject = execute(request(url, token).get().build()) { Json.parseToJsonElement(it.body!!.string()).jsonObject }

    override suspend fun form(
        url: String,
        values: Map<String, String>,
    ): JsonObject {
        require(java.net.URI(url).host == "auth.openai.com")
        return execute(request(url).post(FormBody.Builder().apply { values.forEach { (k, v) -> add(k, v) } }.build()).build()) {
            val text = it.body!!.string()
            if (text.isBlank()) buildJsonObject {} else Json.parseToJsonElement(text).jsonObject
        }
    }

    override suspend fun responses(
        token: String,
        body: JsonObject,
        onDelta: (String) -> Unit,
    ): JsonObject =
        execute(
            request("https://api.openai.com/v1/responses", token)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build(),
        ) { response ->
            response.body!!.charStream().buffered().use { readResponseStream(it, onDelta) }
        }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun request(
        url: String,
        token: String? = null,
    ): Request.Builder {
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && uri.port == -1 && uri.rawUserInfo == null && uri.host in setOf("auth.openai.com", "api.openai.com"))
        require(token == null || uri.host == "api.openai.com")
        return Request.Builder().url(url).apply { token?.let { header("Authorization", "Bearer $it") } }
    }

    private suspend fun <T> execute(
        request: Request,
        read: (Response) -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        error: IOException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        try {
                            val result =
                                response.use {
                                    if (!it.isSuccessful) {
                                        val body = runCatching { Json.parseToJsonElement(it.body!!.string()).jsonObject }.getOrNull()
                                        throw openAiHttpFailure(request, it.code, body)
                                    }
                                    read(it)
                                }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                },
            )
        }
}

/** EOF without the terminal success event must never commit a conversation turn. */
internal fun readResponseStream(
    reader: java.io.BufferedReader,
    onDelta: (String) -> Unit,
): JsonObject {
    var completed: JsonObject? = null
    val data = StringBuilder()
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) {
            if (data.toString() == "[DONE]") {
                data.setLength(0)
                continue
            }
            if (data.isNotEmpty()) {
                val event = Json.parseToJsonElement(data.toString()).jsonObject
                when (event.textOrNull("type")) {
                    "response.output_text.delta" -> onDelta(event.textOrNull("delta").orEmpty())
                    "response.completed" -> completed = event["response"]!!.jsonObject
                    "response.failed", "response.incomplete", "error" -> throw OpenAiFailure(event["response"]?.jsonObject?.get("error")?.jsonObject?.textOrNull("code") ?: event.textOrNull("code") ?: "response_failed")
                }
                data.setLength(0)
            }
        } else if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.substringAfter(':').trimStart())
            require(data.length < 4_000_000) { "Response event is too large" }
        }
    }
    return completed ?: throw OpenAiFailure("incomplete_stream")
}

/** Admission failures do not always carry the API's structured error object. */
internal fun openAiHttpFailure(
    request: Request,
    status: Int,
    body: JsonObject?,
): OpenAiFailure {
    val value = body?.get("error")
    val codeValue = if (value is JsonObject) value["code"] as? JsonPrimitive else value as? JsonPrimitive
    val code = codeValue?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    val inference = request.method == "POST" && request.url.host == "api.openai.com" && request.url.encodedPath == "/v1/responses"
    return OpenAiFailure(code ?: "request_failed", status, inferenceRestricted = inference && status == 403 && code == null)
}
