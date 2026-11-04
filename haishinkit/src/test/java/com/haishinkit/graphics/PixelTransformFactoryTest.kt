package com.haishinkit.graphics

import com.haishinkit.codec.VideoCodec
import com.haishinkit.view.HkSurfaceView
import com.haishinkit.view.HkTextureView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PixelTransformFactoryTest {
    @Test
    fun codecAndDirectCreationUseSelectedBackendWithoutRecursingIntoBridge() {
        val context = RuntimeEnvironment.getApplication()
        val original = PixelTransform.factory
        val created = mutableListOf<PixelTransform>()
        try {
            PixelTransform.factory = {
                PixelTransform.createOpenGL(it).also { transform -> created.add(transform) }
            }
            val direct = PixelTransform.create(context)
            assertSame(created[0], direct)
            val codec = VideoCodec(context)
            val codecTransform = codec.pixelTransform
            assertSame(created[1], codecTransform)
            HkSurfaceView(context).frameRate = 24
            HkTextureView(context).frameRate = 24
            assertEquals(4, created.size)
        } finally {
            PixelTransform.factory = original
            created.forEach { it.close() }
        }
    }
}
