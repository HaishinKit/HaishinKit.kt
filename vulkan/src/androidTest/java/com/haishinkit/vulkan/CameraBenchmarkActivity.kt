package com.haishinkit.vulkan

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/** Foreground permission context for the opt-in camera measurement. */
class CameraBenchmarkActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setBackgroundColor(0xff101010.toInt())
        window.attributes = window.attributes.apply { preferredRefreshRate = 60f }
    }
}
