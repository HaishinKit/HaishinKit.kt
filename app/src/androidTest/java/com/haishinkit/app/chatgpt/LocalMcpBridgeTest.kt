package com.haishinkit.app.chatgpt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.graphics.DynamicRangeMode
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMcpBridgeTest {
    @Test
    fun officialClientCapturesSyntheticCompositionAndReleasesPort() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            repeat(2) {
                val bridge = LocalMcpBridge(SyntheticScreen(context))
                try {
                    withTimeout(20_000) {
                        val tools = bridge.open()
                        assertTrue(tools.toString().contains("capture_frame"))
                        val reply =
                            bridge.call(
                                "capture_frame",
                                buildJsonObject {
                                    put("maxWidth", 80)
                                    put("quality", 75)
                                },
                            )
                        val bytes = Base64.decode(reply.images.single().substringAfter(','), Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        try {
                            assertEquals(80, bitmap.width)
                            assertEquals(45, bitmap.height)
                        } finally {
                            bitmap.recycle()
                        }
                        bridge.detach()
                        assertTrue(bridge.call("capture_frame", buildJsonObject {}).images.isEmpty())
                    }
                } finally {
                    bridge.close()
                    bridge.close()
                }
            }
        }

    private class SyntheticScreen(context: Context) : Screen(context, DynamicRangeMode.SDR) {
        override fun readPixels(lambda: (Bitmap?) -> Unit) {
            lambda(Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) })
        }

        override fun attachVideo(
            track: Int,
            video: VideoSource?,
        ) = Unit

        override fun bind(screenObject: ScreenObject) = Unit

        override fun unbind(screenObject: ScreenObject) = Unit
    }
}
