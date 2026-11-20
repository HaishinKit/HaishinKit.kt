package com.haishinkit.app

import android.content.Context
import android.widget.Toast
import com.haishinkit.screen.Screen

/** Bridges source screens to the Example capture module without changing library code. */
internal object QualityCapture {
    fun save(
        context: Context,
        screen: Screen,
        source: String,
    ) {
        runCatching {
            Class.forName("com.haishinkit.app.vqmetrix.MixerCapture")
                .getMethod("start", Context::class.java, Screen::class.java, String::class.java)
                .invoke(null, context, screen, source)
        }.onFailure { Toast.makeText(context, it.cause?.message ?: it.message, Toast.LENGTH_LONG).show() }
    }

    fun stop(source: String) {
        if (!BuildConfig.VQMETRIX_AVAILABLE) return
        runCatching {
            Class.forName("com.haishinkit.app.vqmetrix.MixerCapture").getMethod("stop", String::class.java).invoke(null, source)
        }
    }
}
