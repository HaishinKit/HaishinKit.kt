package com.haishinkit.media

import com.haishinkit.screen.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MediaOutputRegistryTest {
    private val source =
        object : MediaOutputDataSource {
            override val hasAudio = true
            override val hasVideo = false
            override val screen: Screen get() = error("Rendering is not used")

            override fun registerOutput(output: MediaOutput) {}

            override fun unregisterOutput(output: MediaOutput) {}
        }

    @Test
    fun updatingOutputsDuringAnAudioDeliveryDoesNotInvalidateItsSnapshot() {
        val registry = MediaOutputRegistry(source)
        val first = Output()
        val previous = Output()
        val replacement = Output()
        registry.register(first)
        registry.register(previous)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val delivery =
                threads.submit<List<MediaOutput>> {
                    val delivered = mutableListOf<MediaOutput>()
                    registry.forEach { output ->
                        if (output === first) {
                            entered.countDown()
                            assertTrue(resume.await(3, TimeUnit.SECONDS))
                        }
                        delivered.add(output)
                    }
                    delivered
                }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            threads.submit {
                registry.unregister(previous)
                registry.register(replacement)
                registry.register(replacement)
            }.get(3, TimeUnit.SECONDS)
            resume.countDown()
            assertEquals(listOf(first, previous), delivery.get(3, TimeUnit.SECONDS))
            val next = mutableListOf<MediaOutput>()
            registry.forEach { next.add(it) }
            assertEquals(listOf(first, replacement), next)
            assertNull(previous.dataSource)
            assertSame(source, replacement.dataSource!!.get())
            registry.clear()
            assertNull(first.dataSource)
            assertNull(replacement.dataSource)
        } finally {
            resume.countDown()
            threads.shutdownNow()
        }
    }

    private class Output : MediaOutput {
        override var dataSource: WeakReference<MediaOutputDataSource>? = null

        override fun append(buffer: MediaBuffer) {}
    }
}
