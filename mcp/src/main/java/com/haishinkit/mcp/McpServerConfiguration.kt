package com.haishinkit.mcp

import java.util.UUID

/**
 * Configuration for a loopback-only MCP server. Use a distinct [port] per server instance.
 * [bearerToken] must be securely shared with the authorized client, never published in URLs.
 * The generated token is instance-specific and is not persisted or logged by the library.
 */
public class McpServerConfiguration(
    public val port: Int = 8080,
    public val bearerToken: String = UUID.randomUUID().toString(),
) {
    init {
        require(port in 1..65535) { "port must be in 1..65535." }
        require(bearerToken.matches(Regex("[A-Za-z0-9._~-]{32,256}"))) {
            "bearerToken must contain 32..256 ASCII letters, digits, or . _ ~ - characters."
        }
    }
}
