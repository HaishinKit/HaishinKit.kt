package com.haishinkit.vqmetrix

import android.graphics.Bitmap
import android.os.SystemClock
import com.haishinkit.screen.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/** Samples the existing mixer composition, including camera, projection and overlays. */
class ScreenReference(private val screen: Screen, private val source: String) {
    suspend fun record(
        file: File,
        config: BatchConfig,
        frame: (Int, ReferenceSpec, ByteArray) -> Unit,
    ): CapturedReference {
        val timestamps = ArrayList<Long>()
        var spec: ReferenceSpec? = null
        var writer: Y4mWriter? = null
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            withTimeout(config.seconds * 1000L + 15_000) {
                repeat(config.seconds * config.fps) { index ->
                    val deadline = start + index * 1_000_000_000L / config.fps
                    delay(((deadline - SystemClock.elapsedRealtimeNanos()) / 1_000_000).coerceAtLeast(0))
                    val bitmap =
                        withTimeout(3_000) {
                            suspendCancellableCoroutine { continuation ->
                                screen.readPixels { bitmap ->
                                    if (bitmap == null) {
                                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("No mixer frame is available"))
                                    } else {
                                        continuation.resume(bitmap) { _, value, _ -> value.recycle() }
                                    }
                                }
                            }
                        }
                    timestamps.add(SystemClock.elapsedRealtimeNanos())
                    try {
                        if (spec == null) {
                            val scale = min(1.0, min(config.width.toDouble() / bitmap.width, config.height.toDouble() / bitmap.height))
                            val width = ((bitmap.width * scale).toInt() / 2 * 2).coerceAtLeast(2)
                            val height = ((bitmap.height * scale).toInt() / 2 * 2).coerceAtLeast(2)
                            spec = ReferenceSpec(width, height, config.fps, config.seconds * config.fps)
                            writer = Y4mWriter(file, checkNotNull(spec))
                        }
                        val actual = checkNotNull(spec)
                        val scaled = Bitmap.createScaledBitmap(bitmap, actual.width, actual.height, true)
                        val yuv =
                            try {
                                YuvImages.fromBitmap(scaled)
                            } finally {
                                if (scaled !== bitmap) scaled.recycle()
                            }
                        checkNotNull(writer).append(yuv)
                        frame(index + 1, actual, yuv)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            writer?.close()
        }
        return CapturedReference(checkNotNull(spec), "mixer-$source", timestamps, "limited", "bt709")
    }
}
