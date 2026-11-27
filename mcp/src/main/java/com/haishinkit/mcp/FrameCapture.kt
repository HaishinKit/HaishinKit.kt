package com.haishinkit.mcp

import android.graphics.Bitmap
import android.util.Base64
import com.haishinkit.screen.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Captures an explicitly attached Screen without starting cameras or owning its lifecycle. */
internal class FrameCapture {
    data class Image(
        val data: String,
        val width: Int,
        val height: Int,
        val renderer: String,
        val readbackCompletedAtMillis: Long,
    )

    private class Source(val owner: Any, val screen: Screen)

    private val lock = Any()
    private val captureMutex = Mutex()
    private var source: Source? = null

    fun attach(
        owner: Any,
        screen: Screen,
    ) {
        synchronized(lock) {
            if (source?.owner !== owner) source = Source(owner, screen)
        }
    }

    fun detach(owner: Any) {
        synchronized(lock) {
            if (source?.owner === owner) source = null
        }
    }

    suspend fun capture(
        maxWidth: Int,
        quality: Int,
    ): Image =
        withContext(Dispatchers.Default) {
            withTimeout(3_000) {
                captureMutex.withLock {
                    val current = synchronized(lock) { checkNotNull(source) { "No screen is attached. Attach an active Screen before capturing a frame." } }
                    val (bitmap, completedAt) = readBitmap(current)
                    try {
                        val width = minOf(maxWidth, bitmap.width)
                        val height = (bitmap.height.toDouble() * width / bitmap.width).roundToInt().coerceAtLeast(1)
                        val scaled = if (width == bitmap.width) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
                        try {
                            val bytes = ByteArrayOutputStream()
                            check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, bytes)) { "Could not encode the captured frame." }
                            synchronized(lock) {
                                check(source === current) { "The screen was detached during capture." }
                            }
                            Image(
                                data = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP),
                                width = scaled.width,
                                height = scaled.height,
                                renderer = current.screen.javaClass.simpleName,
                                readbackCompletedAtMillis = completedAt,
                            )
                        } finally {
                            if (scaled !== bitmap) scaled.recycle()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }

    private suspend fun readBitmap(current: Source): Pair<Bitmap, Long> =
        suspendCancellableCoroutine { continuation ->
            // Enqueue the OpenGL read before detach/dispose can enqueue resource release.
            // Vulkan completes the read synchronously on its worker. Compression runs here on Default.
            synchronized(lock) {
                if (source !== current) {
                    continuation.resumeWithException(IllegalStateException("The screen is no longer attached."))
                } else {
                    try {
                        current.screen.readPixels { bitmap ->
                            if (bitmap == null) {
                                continuation.resumeWithException(IllegalStateException("No composition frame is available yet."))
                            } else {
                                continuation.resume(bitmap to System.currentTimeMillis()) { _, value, _ -> value.first.recycle() }
                            }
                        }
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }
            }
        }
}
