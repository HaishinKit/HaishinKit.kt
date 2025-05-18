package com.haishinkit.rtmp.message

import com.haishinkit.rtmp.RtmpConnection
import com.haishinkit.rtmp.RtmpObjectEncoding
import com.haishinkit.rtmp.amf.AmfTypeBuffer
import java.nio.ByteBuffer

/**
 * 7.1.2. Data Message (18, 15)
 */
internal class RtmpDataMessage(
    objectEncoding: RtmpObjectEncoding,
) : RtmpMessage(objectEncoding.dataType) {
    var handlerName: String? = null
    var arguments: ArrayList<Any?> = ArrayList()
    override var length: Int = CAPACITY

    override fun encode(buffer: ByteBuffer): RtmpMessage {
        val serializer = AmfTypeBuffer(buffer)
        serializer.putString(handlerName)
        if (arguments.isNotEmpty()) {
            for (argument in arguments) {
                serializer.putData(argument)
            }
        }
        return this
    }

    override fun decode(buffer: ByteBuffer): RtmpMessage {
        val eom = buffer.position() + length
        val deserializer = AmfTypeBuffer(buffer)
        handlerName = deserializer.string
        val arguments = arguments
        while (buffer.position() < eom) {
            arguments.add(deserializer.data)
        }
        return this
    }

    override fun execute(connection: RtmpConnection): RtmpMessage = this

    companion object {
        private const val CAPACITY = 1024
    }
}
