package com.haishinkit.rtmp.event

/**
 * Helpers for reading RTMP event payloads.
 */
object EventUtils {
    /**
     * Returns the payload as a status map, or an empty map if the payload is not a map.
     */
    fun toMap(event: Event): Map<String, Any> {
        val data = event.data
        if (data == null || data !is Map<*, *>) {
            return HashMap()
        }
        return data as Map<String, Any>
    }
}
