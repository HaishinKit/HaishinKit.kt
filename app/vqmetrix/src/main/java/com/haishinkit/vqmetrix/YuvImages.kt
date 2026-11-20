package com.haishinkit.vqmetrix

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

object YuvImages {
    /** Converts composited SDR RGB to limited-range BT.709 planar YUV420. */
    fun fromBitmap(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        require(width % 2 == 0 && height % 2 == 0)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = ByteArray(width * height * 3 / 2)

        fun channel(
            pixel: Int,
            shift: Int,
        ) = (pixel shr shift) and 255
        for (y in 0 until height) for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            val r = channel(pixel, 16)
            val g = channel(pixel, 8)
            val b = channel(pixel, 0)
            output[y * width + x] = (16 + (0.182586 * r + 0.614231 * g + 0.062007 * b).toInt()).coerceIn(16, 235).toByte()
            if (x % 2 == 0 && y % 2 == 0) {
                var cb = 0.0
                var cr = 0.0
                for (dy in 0..1) for (dx in 0..1) {
                    val color = pixels[(y + dy) * width + x + dx]
                    val red = channel(color, 16)
                    val green = channel(color, 8)
                    val blue = channel(color, 0)
                    cb += -0.100644 * red - 0.338572 * green + 0.439216 * blue
                    cr += 0.439216 * red - 0.398942 * green - 0.040274 * blue
                }
                val index = y / 2 * (width / 2) + x / 2
                output[width * height + index] = (128 + (cb / 4).toInt()).coerceIn(16, 240).toByte()
                output[width * height * 5 / 4 + index] = (128 + (cr / 4).toInt()).coerceIn(16, 240).toByte()
            }
        }
        return output
    }

    fun read(image: Image): ByteArray {
        require(image.format == ImageFormat.YUV_420_888)
        val crop = image.cropRect
        val w = crop.width()
        val h = crop.height()
        require(w % 2 == 0 && h % 2 == 0)
        val output = ByteArray(w * h * 3 / 2)
        var offset = 0
        image.planes.forEachIndexed { planeIndex, plane ->
            val divisor = if (planeIndex == 0) 1 else 2
            val width = w / divisor
            val height = h / divisor
            val buffer = plane.buffer.duplicate()
            val start = buffer.position() + crop.top / divisor * plane.rowStride + crop.left / divisor * plane.pixelStride
            copyPlaneToArray(buffer, start, plane.rowStride, plane.pixelStride, width, height, output, offset)
            offset += width * height
        }
        return output
    }

    fun write(
        image: Image,
        data: ByteArray,
        spec: ReferenceSpec,
    ) {
        require(image.format == ImageFormat.YUV_420_888)
        require(image.cropRect.width() >= spec.width && image.cropRect.height() >= spec.height)
        require(data.size == spec.frameBytes)
        var offset = 0
        image.planes.forEachIndexed { planeIndex, plane ->
            val divisor = if (planeIndex == 0) 1 else 2
            val width = spec.width / divisor
            val height = spec.height / divisor
            val buffer = plane.buffer.duplicate()
            val start = buffer.position() + image.cropRect.top / divisor * plane.rowStride + image.cropRect.left / divisor * plane.pixelStride
            for (y in 0 until height) {
                for (x in 0 until width) buffer.put(start + y * plane.rowStride + x * plane.pixelStride, data[offset + y * width + x])
            }
            offset += width * height
        }
    }

    fun preview(
        bytes: ByteArray,
        spec: ReferenceSpec,
    ): Bitmap {
        val width = minOf(240, spec.width)
        val height = maxOf(1, spec.height * width / spec.width)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sx = x * spec.width / width
                val sy = y * spec.height / height
                val luma = bytes[sy * spec.width + sx].toInt() and 255
                // A neutral luma preview avoids assuming an unknown camera color matrix.
                pixels[y * width + x] = (255 shl 24) or (luma shl 16) or (luma shl 8) or luma
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    internal fun copyPlaneToArray(
        buffer: ByteBuffer,
        start: Int,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        destination: ByteArray,
        destinationOffset: Int,
    ) {
        for (y in 0 until height) {
            for (x in 0 until width) destination[destinationOffset + y * width + x] = buffer.get(start + y * rowStride + x * pixelStride)
        }
    }
}
