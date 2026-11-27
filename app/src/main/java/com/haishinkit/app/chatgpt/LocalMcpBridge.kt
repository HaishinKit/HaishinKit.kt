package com.haishinkit.app.chatgpt

import com.haishinkit.mcp.McpServer
import com.haishinkit.mcp.McpServerConfiguration
import com.haishinkit.screen.Screen
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class McpToolReply(val text: String, val images: List<String> = emptyList())

/** The MCP token belongs to this local connection and is never sent to OpenAI. */
internal class LocalMcpBridge(private val screen: Screen) {
    private val server = McpServer(McpServerConfiguration(port = 8081))
    private val http =
        HttpClient(OkHttp) {
            install(SSE)
            defaultRequest { headers.append("Authorization", "Bearer ${server.configuration.bearerToken}") }
        }
    private val client = Client(Implementation(name = "haishinkit-ai-chat", version = "1.0"))
    private val closeMutex = Mutex()
    private var closed = false
    private var allowedTools: Set<String> = emptySet()

    suspend fun open(): JsonArray {
        server.attachScreen(screen)
        try {
            server.start()
            client.connect(StreamableHttpClientTransport(http, server.endpoint))
            val tools = client.listTools().tools.filter { it.name in setOf("capture_frame", "hello_android") }
            allowedTools = tools.map { it.name }.toSet()
            return JsonArray(
                tools.map { tool ->
                    buildJsonObject {
                        put("type", "function")
                        put("name", tool.name)
                        put("description", tool.description.orEmpty())
                        put("parameters", McpJson.encodeToJsonElement(ToolSchema.serializer(), tool.inputSchema))
                        put("strict", false)
                    }
                },
            )
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    suspend fun call(
        name: String,
        arguments: JsonObject,
    ): McpToolReply {
        require(name in allowedTools) { "Tool is not exposed by this sample" }
        val result = client.callTool(CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)))
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        val images =
            if (result.isError == true) {
                emptyList()
            } else {
                result.content.filterIsInstance<ImageContent>().map { image ->
                    require(image.mimeType == "image/jpeg" && image.data.length < 3_000_000)
                    "data:${image.mimeType};base64,${image.data}"
                }
            }
        return McpToolReply(if (result.isError == true) "Tool failed: $text" else text.ifBlank { result.structuredContent?.toString() ?: "Completed" }, images)
    }

    /** Invalidate immediately on the controller thread, before the mixer stops/disposes. */
    fun detach() {
        server.detachScreen(screen)
    }

    suspend fun close(): Unit =
        withContext(NonCancellable + Dispatchers.IO) {
            closeMutex.withLock {
                if (closed) return@withLock
                closed = true
                detach()
                try {
                    client.close()
                } finally {
                    http.close()
                    server.stop()
                }
            }
        }
}
