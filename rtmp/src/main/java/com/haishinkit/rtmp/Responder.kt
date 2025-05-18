package com.haishinkit.rtmp

/**
 * A interface that response for an [com.haishinkit.rtmp.RtmpConnection].call().
 */
interface Responder {
    fun onResult(arguments: List<Any?>)

    fun onStatus(arguments: List<Any?>)

    companion object {
        val NULL =
            object : Responder {
                override fun onResult(arguments: List<Any?>) {
                }

                override fun onStatus(arguments: List<Any?>) {
                }
            }
    }
}
