package com.haishinkit.rtmp.amf

import junit.framework.TestCase

class AmfEcmaArrayTest : TestCase() {
    fun testSetAndGet() {
        val ecmaArray = AmfEcmaArray()
        ecmaArray[0.toString()] = "Hello World!!"
        ecmaArray[1.toString()] = "World!!"
        assertEquals("Hello World!!", ecmaArray[0.toString()])
        assertEquals("World!!", ecmaArray[1.toString()])
    }
}
