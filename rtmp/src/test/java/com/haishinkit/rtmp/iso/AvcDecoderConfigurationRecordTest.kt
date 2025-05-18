package com.haishinkit.rtmp.iso

import junit.framework.TestCase
import java.nio.ByteBuffer

class AvcDecoderConfigurationRecordTest : TestCase() {
    fun testDecode() {
        val byteBuffer = ByteBuffer.wrap(SAMPLE_DATA_0)
        val record = AvcDecoderConfigurationRecord.decode(byteBuffer)
    }

    companion object {
        private var SAMPLE_DATA_0 =
            byteArrayOf(
                1,
                100,
                0,
                31,
                -1,
                -31,
                0,
                27,
                103,
                100,
                0,
                31,
                -84,
                -39,
                64,
                72,
                5,
                -70,
                106,
                2,
                2,
                2,
                -128,
                0,
                0,
                3,
                0,
                -128,
                0,
                0,
                30,
                71,
                -116,
                24,
                -53,
                1,
                0,
                4,
                104,
                -17,
                -68,
                -80,
            )
    }
}
