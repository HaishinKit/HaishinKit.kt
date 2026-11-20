package com.haishinkit.app

import androidx.lifecycle.DefaultLifecycleObserver

/** Release builds do not include the MCP server or its dependencies. */
internal class DebugMcpServer : DefaultLifecycleObserver
