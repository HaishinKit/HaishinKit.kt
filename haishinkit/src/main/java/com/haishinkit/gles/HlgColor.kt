package com.haishinkit.gles

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** CPU equivalents of the shader's SDR background conversion. */
internal object HlgColor {
    fun fromSrgb(
        red: Float,
        green: Float,
        blue: Float,
    ): FloatArray {
        fun linear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        val r = linear(red)
        val g = linear(green)
        val b = linear(blue)
        return floatArrayOf(
            (0.627404f * r + 0.329283f * g + 0.043313f * b) * 0.203f,
            (0.069097f * r + 0.919540f * g + 0.011362f * b) * 0.203f,
            (0.016391f * r + 0.088013f * g + 0.895595f * b) * 0.203f,
        )
    }

    fun encode(linear: FloatArray): FloatArray {
        val y = 0.2627f * linear[0] + 0.6780f * linear[1] + 0.0593f * linear[2]
        val factor = y.coerceAtLeast(0.000001f).pow(-0.2f / 1.2f)
        return FloatArray(3) {
            val scene = (linear[it] * factor).coerceIn(0f, 1f)
            if (scene <= 1f / 12f) sqrt(3f * scene) else 0.17883277f * ln(12f * scene - 0.28466892f) + 0.55991073f
        }
    }
}
