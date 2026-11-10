package com.haishinkit.rtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Local transport deadlines are failures; cancellation owned by a caller still propagates. */
internal suspend fun <T> withRTCDeadline(
    timeoutMillis: Long,
    message: String,
    block: suspend CoroutineScope.() -> T,
): T {
    val completed = withTimeoutOrNull(timeoutMillis) { DeadlineValue(block()) } ?: throw IOException(message)
    return completed.value
}

private data class DeadlineValue<T>(val value: T)
