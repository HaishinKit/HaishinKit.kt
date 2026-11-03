package com.haishinkit.srt

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SrtLoggerTest {
    @Test
    fun configuresNativeLoggerAndKeepsDefensiveAreaSnapshots() {
        val android = System.getProperty("java.vm.name") == "Dalvik"
        val hostLibrary =
            System.getProperty("java.library.path").orEmpty().split(File.pathSeparator).any {
                File(it, System.mapLibraryName("srt")).isFile
            }
        assumeTrue("Run on Android or supply a host JNI directory", android || hostLibrary)
        val originalLevel = SrtLogger.level
        val originalAreas = SrtLogger.functionalAreas
        try {
            // Exercise JNI binding for all supported levels, without creating a socket.
            SrtLogger.Level.entries.forEach {
                SrtLogger.level = it
                assertEquals(it, SrtLogger.level)
            }
            val selected = mutableSetOf(SrtLogger.FunctionalArea.APPLOG, SrtLogger.FunctionalArea.API_SEND)
            SrtLogger.functionalAreas = selected
            selected.clear()
            val snapshot = SrtLogger.functionalAreas
            assertEquals(setOf(SrtLogger.FunctionalArea.APPLOG, SrtLogger.FunctionalArea.API_SEND), snapshot)
            SrtLogger.functionalAreas = setOf(SrtLogger.FunctionalArea.CONNECTION)
            assertEquals(setOf(SrtLogger.FunctionalArea.APPLOG, SrtLogger.FunctionalArea.API_SEND), snapshot)
            assertEquals(setOf(SrtLogger.FunctionalArea.CONNECTION), SrtLogger.functionalAreas)
        } finally {
            SrtLogger.level = originalLevel
            SrtLogger.functionalAreas = originalAreas
        }
    }
}
