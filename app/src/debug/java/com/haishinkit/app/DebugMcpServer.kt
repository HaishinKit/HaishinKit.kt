package com.haishinkit.app

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.haishinkit.mcp.McpServer
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/** The sample opts into MCP only in its debug build; library users choose their own lifecycle. */
internal class DebugMcpServer : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        worker.execute {
            activeOwners.add(this)
            try {
                runBlocking { server.start() }
                Log.i(TAG, "MCP ready at ${server.endpoint}")
                Log.i(TAG, "MCP bearer token: ${server.configuration.bearerToken}")
            } catch (error: Exception) {
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
        if (activeOwners.isEmpty()) runBlocking { server.stop() }
    }

    companion object {
        val server = McpServer()
        private const val TAG = "DebugMcpServer"
        private val worker = Executors.newSingleThreadExecutor()
        private val activeOwners = mutableSetOf<DebugMcpServer>()
    }
}
