package com.haishinkit.app

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.Executors

/** A local, debug-only MCP endpoint, reachable from a development computer via adb forward. */
internal class DebugMcpServer : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        worker.execute {
            activeOwners.add(this)
            if (stopServer != null) return@execute
            val server =
                embeddedServer(Netty, host = "127.0.0.1", port = PORT) {
                    install(ContentNegotiation) { json(McpJson) }
                    install(
                        createApplicationPlugin("LocalMcpRequests") {
                            onCall { call ->
                                // Native MCP clients do not send Origin; browser requests are not enabled.
                                if (call.request.headers[HttpHeaders.Origin] != null) {
                                    call.respond(HttpStatusCode.Forbidden)
                                } else if (call.request.headers[HttpHeaders.Authorization] != "Bearer $token") {
                                    call.respond(HttpStatusCode.Unauthorized)
                                }
                            }
                        },
                    )
                    mcpStatelessStreamableHttp(
                        enableDnsRebindingProtection = true,
                        allowedHosts = listOf("127.0.0.1:$PORT", "localhost:$PORT"),
                    ) {
                        createServer()
                    }
                }
            try {
                server.start(wait = false)
                stopServer = { server.stop(0, 1_000) }
                Log.i(TAG, "MCP ready at http://127.0.0.1:$PORT/mcp (adb forward tcp:$PORT tcp:$PORT)")
                Log.i(TAG, "MCP bearer token: $token")
            } catch (error: Exception) {
                server.stop(0, 1_000)
                Log.e(TAG, "Could not start debug MCP server", error)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        worker.execute { stop() }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        worker.execute { stop() }
    }

    private fun stop() {
        activeOwners.remove(this)
        if (activeOwners.isNotEmpty()) return
        stopServer?.invoke()
        stopServer = null
    }

    private fun createServer(): Server =
        Server(
            serverInfo = Implementation(name = "haishinkit-android-sample", version = "1.0.0"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
                ),
        ).apply {
            addTool(
                name = "hello_android",
                description = "Check the MCP connection to the running HaishinKit Android sample. Takes no arguments.",
            ) {
                CallToolResult(content = listOf(TextContent("Hello from HaishinKit on Android!")))
            }
            addTool(
                name = "capture_frame",
                description = "Capture the active Publish composition as a JPEG, including ScreenObjects and excluding app controls. Open Publish first. Returns the currently available frame, not a guaranteed new camera frame.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                put("maxWidth", integerSchema(32, 1920, 1280))
                                put("quality", integerSchema(1, 100, 85))
                            },
                    ),
            ) { request -> captureFrame(request) }
        }

    private suspend fun captureFrame(request: CallToolRequest): CallToolResult {
        try {
            require(request.arguments?.keys.orEmpty().all { it == "maxWidth" || it == "quality" }) {
                "Only maxWidth and quality are supported."
            }
            val maxWidth = integerArgument(request, "maxWidth", 1280, 32..1920)
            val quality = integerArgument(request, "quality", 85, 1..100)
            val image = DebugFrameCapture.capture(maxWidth, quality)
            val metadata =
                buildJsonObject {
                    put("width", image.width)
                    put("height", image.height)
                    put("renderer", image.renderer)
                    put("readbackCompletedAtMillis", image.readbackCompletedAtMillis)
                }
            return CallToolResult(
                content = listOf(ImageContent(data = image.data, mimeType = "image/jpeg"), TextContent(metadata.toString())),
                structuredContent = metadata,
            )
        } catch (_: TimeoutCancellationException) {
            return CallToolResult(isError = true, content = listOf(TextContent("Frame capture timed out. Try again with Publish open.")))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return CallToolResult(isError = true, content = listOf(TextContent(error.message ?: "Could not capture the composition.")))
        }
    }

    private fun integerArgument(
        request: CallToolRequest,
        name: String,
        default: Int,
        range: IntRange,
    ): Int {
        val value = request.arguments?.get(name) ?: return default
        val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        require(number != null && number in range) { "$name must be an integer in ${range.first}..${range.last}." }
        return number
    }

    private fun integerSchema(
        minimum: Int,
        maximum: Int,
        default: Int,
    ) =
        buildJsonObject {
            put("type", "integer")
            put("minimum", minimum)
            put("maximum", maximum)
            put("default", default)
        }

    private companion object {
        const val TAG = "DebugMcpServer"
        const val PORT = 8080
        val token: String = UUID.randomUUID().toString()

        // Activity replacement can overlap start/stop callbacks. Serialize ownership across instances.
        val worker = Executors.newSingleThreadExecutor()
        val activeOwners = mutableSetOf<DebugMcpServer>()
        var stopServer: (() -> Unit)? = null
    }
}
