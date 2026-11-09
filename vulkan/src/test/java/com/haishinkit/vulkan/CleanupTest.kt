package com.haishinkit.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CleanupTest {
    @Test
    fun failingDetachDoesNotPreventRemainingResourcesFromClosing() {
        val attempted = mutableListOf<String>()
        val detach = IllegalStateException("detach")
        val renderer = IllegalStateException("renderer")
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                cleanupAll(
                    listOf(
                        {
                            attempted.add("input1")
                            throw detach
                        },
                        { attempted.add("input2") },
                        {
                            attempted.add("renderer")
                            throw renderer
                        },
                        { attempted.add("children") },
                    ),
                )
            }
        assertEquals(listOf("input1", "input2", "renderer", "children"), attempted)
        assertSame(detach, thrown)
        assertEquals(listOf(renderer), thrown.suppressed.toList())
    }

    @Test
    fun repeatedSameFailureDoesNotAbortCleanupWithSelfSuppression() {
        val error = IllegalStateException("same")
        var finished = false
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                cleanupAll(listOf({ throw error }, { throw error }, { finished = true }))
            }
        assertSame(error, thrown)
        assertEquals(true, finished)
        assertEquals(0, thrown.suppressed.size)
    }
}
