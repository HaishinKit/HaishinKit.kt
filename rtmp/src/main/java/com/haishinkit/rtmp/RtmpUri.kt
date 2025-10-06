package com.haishinkit.rtmp

import android.net.Uri
import android.util.Log

internal class RtmpUri(
    private val uri: Uri,
) {
    init {
        Log.i(TAG, "")
        Log.i(TAG, "┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓")
        Log.i(TAG, "┃  [RtmpUri.kt] URL PARSING STARTED         ┃")
        Log.i(TAG, "┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛")
        Log.i(TAG, "")
        Log.i(TAG, "[RtmpUri.kt] 📥 Input URI:")
        Log.i(TAG, "[RtmpUri.kt]    Full URI: $uri")
        Log.i(TAG, "[RtmpUri.kt]    Scheme: ${uri.scheme}")
        Log.i(TAG, "[RtmpUri.kt]    Host: ${uri.host}")
        Log.i(TAG, "[RtmpUri.kt]    Port: ${uri.port}")
        Log.i(TAG, "[RtmpUri.kt]    Path: ${uri.path}")
        Log.i(TAG, "[RtmpUri.kt]    Query: ${uri.query}")
        Log.i(TAG, "")
        Log.i(TAG, "[RtmpUri.kt] 🔍 Path Segments (split by '/'):")
        uri.pathSegments.forEachIndexed { index, segment ->
            Log.i(TAG, "[RtmpUri.kt]    [$index] = \"$segment\"")
        }
        Log.i(TAG, "")
        Log.i(TAG, "[RtmpUri.kt] 🎯 Parsing Results:")
        Log.i(TAG, "[RtmpUri.kt]    tcUrl (for connection): $tcUrl")
        Log.i(TAG, "[RtmpUri.kt]    streamName (for publish): $streamName")
        Log.i(TAG, "[RtmpUri.kt] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    val streamName: String
        get() {
            // Extract the last path segment as stream name (without query params)
            val result = uri.pathSegments.lastOrNull()?.toString() ?: ""
            Log.d(TAG, "[RtmpUri.kt]    → streamName accessed: \"$result\"")
            return result
        }

    val tcUrl: String
        get() {
            // Build connection URL with all path segments EXCEPT the last one
            val pathSegments = uri.pathSegments
            if (pathSegments.isEmpty()) {
                val result = uri.buildUpon().path("/").clearQuery().build().toString()
                Log.d(TAG, "[RtmpUri.kt]    → tcUrl accessed (empty): \"$result\"")
                return result
            }

            // Get all segments except the last (which is streamName)
            val appPath = if (pathSegments.size > 1) {
                pathSegments.dropLast(1).joinToString("/")
            } else {
                // If only one segment, use it as app path
                pathSegments.first()
            }

            // Build the tcUrl with query parameters included
            val builder = uri.buildUpon().path("/$appPath")

            // Keep query parameters in the connection URL
            // Query params are already part of uri, so no need to re-add

            val result = builder.build().toString()
            Log.d(TAG, "[RtmpUri.kt]    → tcUrl accessed: \"$result\"")
            return result
        }

    override fun toString() = uri.toString()

    companion object {
        private const val TAG = "RtmpUri"
    }
}
