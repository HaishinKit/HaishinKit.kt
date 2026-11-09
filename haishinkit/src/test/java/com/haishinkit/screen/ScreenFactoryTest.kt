package com.haishinkit.screen

import com.haishinkit.media.MediaMixer
import com.haishinkit.stream.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ScreenFactoryTest {
    @Test
    fun mixersAndStreamsUseSelectedScreenBackend() {
        val context = RuntimeEnvironment.getApplication()
        val original = Screen.factory
        val created = mutableListOf<Screen>()
        try {
            Screen.factory = { MockScreen(it).also { screen -> created.add(screen) } }
            val direct = Screen.create(context)
            val mixer = MediaMixer(context)
            val stream =
                object : Stream(context) {
                    override fun close() {}
                }
            val mixerScreen = mixer.screen
            assertSame(created[0], direct)
            assertSame(created[1], stream.screen)
            assertSame(created[2], mixerScreen)
            assertEquals(3, created.size)
        } finally {
            Screen.factory = original
            created.forEach { it.dispose() }
        }
    }
}
