package com.haishinkit.rtmp

/**
 * Reports an RTMP session failure.
 *
 * @param message The RTMP status code, or an empty string for an I/O failure without a status code.
 */
class RtmpStatusException(
    message: String,
) : Throwable(message)
