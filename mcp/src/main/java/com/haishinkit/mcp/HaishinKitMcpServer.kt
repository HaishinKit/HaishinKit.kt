package com.haishinkit.mcp

import com.haishinkit.screen.Screen
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * An opt-in, loopback-only MCP Streamable HTTP server for Android compositions.
 *
 * No socket is opened until [start]. The caller owns Screen/camera permission and lifecycle;
 * detach the Screen before stopping or disposing its mixer. No token or image is logged.
 *
 * [configureTools] registers application-owned tools using the official MCP SDK. It is called
 * for each stateless request; register handlers only, without performing operations here.
 * Tool handlers must enforce their application's permission and threading requirements.
 * The built-in names `hello_android` and `capture_frame` are reserved.
 */
public class HaishinKitMcpServer(
    public val configuration: McpServerConfiguration = McpServerConfiguration(),
    private val configureTools: Server.() -> Unit = {},
) {
    /** Whether the endpoint has successfully started. */
    @Volatile
    public var isRunning: Boolean = false
        private set

    /** Local URL; forward this port with adb to connect from a development computer. */
    public val endpoint: String = "http://127.0.0.1:${configuration.port}/mcp"

    private val lifecycleMutex = Mutex()
    private val frameCapture = FrameCapture()
    private var stopEngine: (() -> Unit)? = null

    /** Makes [screen] available to capture_frame. Does not start capture or own the Screen. */
    public fun attachScreen(screen: Screen) {
        frameCapture.attach(screen, screen)
    }

    /** Invalidates captures from [screen]. Call before stopping or disposing its mixer. */
    public fun detachScreen(screen: Screen) {
        frameCapture.detach(screen)
    }

    /**
     * Starts the endpoint off the UI thread. Idempotent; bind/setup failures are thrown.
     *
     * The endpoint has an independent lifetime: cancellation during blocking startup or
     * delivery of its result can leave it running. A single lifecycle owner should put
     * this call inside a `try` block and always call [stop] from its `finally` block.
     */
    public suspend fun start(): Unit =
        withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                if (isRunning) return@withLock
                val engine = createEngine()
                try {
                    engine.start(wait = false)
                    stopEngine = { engine.stop(0, 1_000) }
                    isRunning = true
                } catch (error: Exception) {
                    runCatching { engine.stop(0, 1_000) }
                    throw error
                }
            }
        }

    /** Stops the endpoint off the UI thread. The attached Screen remains caller-owned. */
    public suspend fun stop(): Unit =
        withContext(NonCancellable + Dispatchers.IO) {
            lifecycleMutex.withLock {
                try {
                    stopEngine?.invoke()
                } finally {
                    stopEngine = null
                    isRunning = false
                }
            }
        }

    // Do not inherit the caller CoroutineScope: its completion must not wait for the server lifetime.
    private fun createEngine() =
        embeddedServer(Netty, host = "127.0.0.1", port = configuration.port) {
            install(ContentNegotiation) { json(McpJson) }
            install(
                createApplicationPlugin("LocalMcpRequests") {
                    onCall { call ->
                        if (call.request.headers[HttpHeaders.Origin] != null) {
                            call.respond(HttpStatusCode.Forbidden)
                        } else if (call.request.headers[HttpHeaders.Authorization] != "Bearer ${configuration.bearerToken}") {
                            call.respond(HttpStatusCode.Unauthorized)
                        }
                    }
                },
            )
            mcpStatelessStreamableHttp(
                enableDnsRebindingProtection = true,
                allowedHosts = listOf("127.0.0.1:${configuration.port}", "localhost:${configuration.port}"),
            ) { createServer() }
        }

    private fun createServer(): Server =
        Server(
            serverInfo = Implementation(name = "haishinkit-android", version = "1.0.0"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
                ),
        ).apply {
            addTool(
                name = "hello_android",
                description = "Check the connection to the HaishinKit Android MCP server. Takes no arguments.",
            ) {
                CallToolResult(content = listOf(TextContent("Hello from HaishinKit on Android!")))
            }
            addTool(
                name = "capture_frame",
                description = "Capture the attached Screen composition as a JPEG, including ScreenObjects and excluding application controls. Returns the currently available frame, not a guaranteed new camera frame.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                put("maxWidth", integerSchema(32, 1920, 1280))
                                put("quality", integerSchema(1, 100, 85))
                            },
                    ),
            ) { request -> captureFrame(request) }
            configureTools()
        }

    private suspend fun captureFrame(request: CallToolRequest): CallToolResult {
        try {
            require(request.arguments?.keys.orEmpty().all { it == "maxWidth" || it == "quality" }) {
                "Only maxWidth and quality are supported."
            }
            val maxWidth = integerArgument(request, "maxWidth", 1280, 32..1920)
            val quality = integerArgument(request, "quality", 85, 1..100)
            val image = frameCapture.capture(maxWidth, quality)
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
            return CallToolResult(isError = true, content = listOf(TextContent("Frame capture timed out. Try again with an attached active Screen.")))
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
}
