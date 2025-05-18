package com.haishinkit.rtmp.iso

import junit.framework.TestCase
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IsoTypeBufferFormatUtilsTest : TestCase() {
    fun testToByteStream() {
        val byteBuffer =
            ByteBuffer.wrap(SAMPLE_DATA_0).apply {
                order(ByteOrder.BIG_ENDIAN)
            }
        byteBuffer.rewind()
        IsoTypeBufferUtils.toByteStream(byteBuffer, 4)
    }

    companion object {
        private var SAMPLE_DATA_0 =
            byteArrayOf(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                28,
                65,
                -18,
                1,
                -16,
                3,
                -88,
                -27,
                -21,
                4,
                28,
                20,
                123,
                -17,
                114,
                -69,
                -81,
                0,
                39,
                -36,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                28,
                65,
                -18,
                1,
                -16,
                3,
                -88,
                -27,
                -21,
                4,
                28,
                20,
                123,
                -17,
                114,
                -69,
                -81,
                0,
                39,
                -36,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
            )
    }
}
