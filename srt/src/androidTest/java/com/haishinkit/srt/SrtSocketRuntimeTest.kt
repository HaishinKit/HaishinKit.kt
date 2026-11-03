package com.haishinkit.srt

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SrtSocketRuntimeTest {
    @Test
    fun loadsNativeRuntime() {
        assertEquals("1.5.7", SrtSocket.version)
    }
}
