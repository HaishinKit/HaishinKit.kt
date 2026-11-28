package com.haishinkit.app

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/** Foreground only during opt-in physical microphone tests. */
class BluetoothMicrophoneTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            TextView(this).apply {
                text = "HaishinKit Bluetooth microphone test\n\nMicrophone capture is active during testing.\nOnly route, level and timing measurements are retained."
                textSize = 24f
                setPadding(32, 80, 32, 32)
            },
        )
    }
}
