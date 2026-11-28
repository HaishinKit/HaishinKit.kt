package com.haishinkit.rnnoise

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

class NoiseSuppressionTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "HaishinKit: checking OS noise suppression. Audio is not saved." })
    }
}
