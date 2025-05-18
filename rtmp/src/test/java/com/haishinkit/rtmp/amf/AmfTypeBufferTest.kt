package com.haishinkit.rtmp.amf

import com.haishinkit.util.toHexString
import junit.framework.TestCase
import java.nio.ByteBuffer
import kotlin.toString

class AmfTypeBufferTest : TestCase() {
    fun testString() {
        val expected = "Hello World!!"
        val buffer = ByteBuffer.allocate(128)
        val amf0TypeBuffer = AmfTypeBuffer(buffer)
        amf0TypeBuffer.putString(expected)
        buffer.flip()
        assertEquals(expected, amf0TypeBuffer.string)
    }

    fun testString_2() {
        val expected = "Hello World!!"
        val expected2 = "Hello World!!Hello World!!"
        val buffer = ByteBuffer.allocate(128)
        val amf0TypeBuffer = AmfTypeBuffer(buffer)
        amf0TypeBuffer.putString(expected)
        amf0TypeBuffer.putString(expected2)
        buffer.flip()
        assertEquals(expected, amf0TypeBuffer.string)
        assertEquals(expected2, amf0TypeBuffer.string)
    }

    fun testObject() {
        val buffer = ByteBuffer.allocate(128)
        val amf0TypeBuffer = AmfTypeBuffer(buffer)
        val map = mutableMapOf<String, Any?>()
        map["name"] = "shogo4405"
        map["age"] = 20.0
        amf0TypeBuffer.putMap(map)
        buffer.flip()
        assertEquals(map, amf0TypeBuffer.map)
    }

    fun testEmptyObject() {
        val buffer = ByteBuffer.allocate(128)
        val amf0TypeBuffer = AmfTypeBuffer(buffer)
        val map = emptyMap<String, Any?>()
        amf0TypeBuffer.putMap(map)
        buffer.flip()
        assertEquals("03000009", buffer.toHexString())
        assertEquals(map, amf0TypeBuffer.map)
    }

    fun testEcmaArray() {
        val buffer = ByteBuffer.allocate(128)
        val amf0TypeBuffer = AmfTypeBuffer(buffer)
        val array = AmfEcmaArray(listOf("av01", "hevc"))
        amf0TypeBuffer.putEcmaArray(array)
        buffer.flip()
        assertEquals(array.toString(), amf0TypeBuffer.array.toString())
    }

    fun testStrictArray() {
        val buffer = ByteBuffer.allocate(128)
        val amf0TypeBuffer = AmfTypeBuffer(buffer)
        val strings = listOf("av01", "hevc")
        amf0TypeBuffer.putList(strings)
        buffer.flip()
        assertEquals(strings, amf0TypeBuffer.list)
    }
}
