package com.haishinkit.app.chatgpt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenAiStreamTest {
    @Test
    fun requiresTerminalSuccessAndRejectsLateUsageFailure() {
        val partial = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n\n"
        val deltas = StringBuilder()
        val complete = partial + "data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n"
        assertEquals("{\"output\":[]}", readResponseStream(complete.reader().buffered()) { deltas.append(it) }.toString())
        assertEquals("Hello", deltas.toString())
        assertEquals("incomplete_stream", assertThrows(OpenAiFailure::class.java) { readResponseStream(partial.reader().buffered()) {} }.code)
        val failed = partial + "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"
        assertEquals("subscription_sharing_usage_limit_exceeded", assertThrows(OpenAiFailure::class.java) { readResponseStream(failed.reader().buffered()) {} }.code)
    }
}
