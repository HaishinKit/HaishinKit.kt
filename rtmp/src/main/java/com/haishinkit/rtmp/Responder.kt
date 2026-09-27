package com.haishinkit.rtmp

/**
 * Receives responses to commands sent with [RtmpConnection.call].
 */
interface Responder {
    /**
     * Handles the arguments of a successful server response.
     */
    fun onResult(arguments: List<Any?>)

    /**
     * Handles the arguments of a server error response.
     */
    fun onStatus(arguments: List<Any?>)

    companion object {
        /**
         * A responder that ignores both success and error responses.
         */
        val NULL =
            object : Responder {
                override fun onResult(arguments: List<Any?>) {
                }

                override fun onStatus(arguments: List<Any?>) {
                }
            }
    }
}
