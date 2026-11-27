package com.haishinkit.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class McpServerTest {
    @Test
    fun validatesConfigurationWithoutDisclosingTokens() {
        val configuration = McpServerConfiguration()
        assertNotEquals(configuration.bearerToken, McpServerConfiguration().bearerToken)
        assertFalse(configuration.toString().contains(configuration.bearerToken))
        assertTrue(runCatching { McpServerConfiguration(port = 0) }.isFailure)
        assertTrue(runCatching { McpServerConfiguration(bearerToken = "short") }.isFailure)
        assertTrue(runCatching { McpServerConfiguration(bearerToken = "x".repeat(32) + "\r\n") }.isFailure)
    }

    @Test(timeout = 30_000)
    fun servesToolsAndRequiresAuthorizationAcrossRestart() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val server =
                HaishinKitMcpServer(McpServerConfiguration(port = port)) {
                    addTool("example_tool", "An application-owned tool") {
                        CallToolResult(content = listOf(TextContent("Application tool called")))
                    }
                }
            assertFalse(server.isRunning)
            try {
                server.start()
                server.start()
                assertTrue(server.isRunning)
                assertEquals(401, post(server, "{}", "invalid").first)
                assertEquals(403, post(server, "{}", origin = "https://example.invalid").first)
                assertEquals(403, post(server, "{}", host = "example.invalid").first)
                val list = post(server, """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
                assertEquals(200, list.first)
                assertTrue(list.second.contains("capture_frame"))
                assertTrue(list.second.contains("example_tool"))
                val custom = post(server, """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"example_tool","arguments":{}}}""")
                assertEquals(200, custom.first)
                assertTrue(custom.second.contains("Application tool called"))
                val capture = post(server, """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"capture_frame","arguments":{}}}""")
                assertEquals(200, capture.first)
                assertTrue(capture.second.contains("\"isError\":true"))
                assertTrue(capture.second.contains("No screen is attached"))
                server.stop()
                server.stop()
                assertFalse(server.isRunning)
                server.start()
                assertEquals(200, post(server, """{"jsonrpc":"2.0","id":4,"method":"tools/list"}""").first)
            } finally {
                server.stop()
            }
        }

    private fun post(
        server: HaishinKitMcpServer,
        body: String,
        token: String = server.configuration.bearerToken,
        origin: String? = null,
        host: String? = null,
    ): Pair<Int, String> {
        val endpoint = URL(server.endpoint)
        val bytes = body.toByteArray(Charsets.UTF_8)
        return Socket(endpoint.host, endpoint.port).use { socket ->
            socket.soTimeout = 5_000
            val headers =
                buildString {
                    append("POST /mcp HTTP/1.1\r\n")
                    append("Host: ${host ?: "${endpoint.host}:${endpoint.port}"}\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Accept: application/json, text/event-stream\r\n")
                    append("MCP-Protocol-Version: 2025-06-18\r\n")
                    append("Authorization: Bearer $token\r\n")
                    origin?.let { append("Origin: $it\r\n") }
                    append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                }
            socket.getOutputStream().apply {
                write(headers.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
            val response = socket.getInputStream().bufferedReader().readText()
            response.substringBefore("\r\n").split(" ")[1].toInt() to response.substringAfter("\r\n\r\n")
        }
    }
}
