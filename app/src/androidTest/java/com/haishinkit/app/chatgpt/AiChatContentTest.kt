package com.haishinkit.app.chatgpt

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.app.R
import com.haishinkit.app.StudioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class AiChatContentTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sendRequiresAccountModelAndLiveComposition() {
        compose.setContent {
            StudioTheme {
                Surface {
                    Box(Modifier.width(375.dp).height(640.dp)) {
                        AiChatContent(AiChatState(), {}, {}, {}, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText("Continue with ChatGPT").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.ai_prompt)).performTextInput("What is this?")
        compose.onNodeWithText(context.getString(R.string.ai_send)).assertIsNotEnabled()
        snapshot("chatgpt-sign-in.png")
    }

    @Test
    fun composerRemainsUsableInShortWindowWithLargeText() {
        var sent = ""
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                StudioTheme {
                    Surface {
                        Box(Modifier.width(375.dp).height(320.dp)) {
                            AiChatContent(
                                AiChatState(accounts = listOf(ChatGptAccount("fake", "Test account", true)), account = "fake", model = "model", mcpReady = true), {}, {}, {}, {}, {}, {}, { sent = it }, {}, {},
                            )
                        }
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.ai_prompt)).performTextInput("Look at the composition")
        snapshot("chatgpt-large-text-before.png")
        compose.onNodeWithText(context.getString(R.string.ai_send)).assertIsDisplayed().performClick()
        assertEquals("Look at the composition", sent)
        snapshot("chatgpt-large-text.png")
    }

    private fun snapshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
