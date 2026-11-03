package com.haishinkit.srt

import java.io.IOException

/**
 * Native transport failure, captured before thread-local error information changes.
 *
 * @param message Operation and native error description.
 * @property errorCode libsrt error number (the SRT_E* value), not a system errno.
 * @property rejectReason libsrt handshake rejection reason; meaningful for setup
 *   failures and otherwise possibly unknown/zero. See libsrt's SRT_REJ_* constants.
 */
class SrtSocketException(
    message: String,
    val errorCode: Int,
    val rejectReason: Int,
) : IOException(message)
