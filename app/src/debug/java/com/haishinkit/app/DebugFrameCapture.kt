package com.haishinkit.app

import com.haishinkit.screen.Screen

/** Adapts the sample's visible Publish screen to the optional MCP library. */
internal object DebugFrameCapture {
    private var owner: Any? = null
    private var screen: Screen? = null

    fun attach(
        owner: Any,
        screen: Screen,
    ) {
        this.owner = owner
        this.screen = screen
        DebugMcpServer.server.attachScreen(screen)
    }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        screen?.let { DebugMcpServer.server.detachScreen(it) }
        this.owner = null
        screen = null
    }
}
