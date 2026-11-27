package com.haishinkit.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class SampleVideoTest {
    @Test fun cacheContainsTheCompleteBundledSwiftVideoAndIsReused() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = SampleVideo.file(context)
        val expected = context.resources.openRawResource(R.raw.sample_video).use { it.readBytes() }
        assertEquals(expected.size.toLong(), file.length())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        assertEquals(file, SampleVideo.file(context))
    }
}
