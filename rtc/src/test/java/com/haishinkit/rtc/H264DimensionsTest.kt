package com.haishinkit.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class H264DimensionsTest {
    @Test fun acceptsLevel31CodedDimensionsAndCropping() {
        assertEquals(1280 to 720, H264.dimensions(sps(80, 45)))
        assertEquals(854 to 480, H264.dimensions(sps(54, 30, right = 5)))
        assertEquals(2704 to 16, H264.dimensions(sps(169, 1)))
        assertEquals(1280 to 704, H264.dimensions(sps(80, 22, frameOnly = false)))
    }

    @Test fun checksCodedAreaAndAxesBeforeCropping() {
        for (data in listOf(sps(81, 45), sps(170, 1), sps(1, 170), sps(80, 23, frameOnly = false), sps(512, 512, right = 4088, bottom = 4088))) {
            assertThrows(IllegalArgumentException::class.java) { H264.dimensions(data) }
        }
    }

    @Test fun rejectsDimensionsAndCropValuesThatWrapIntArithmetic() {
        for (data in listOf(sps(268_435_536, 45), sps(80, 268_435_501), sps(80, 45, left = 1_073_741_824, right = 1_073_741_824), sps(80, 45, right = 640))) {
            assertThrows(IllegalArgumentException::class.java) { H264.dimensions(data) }
        }
    }

    @Test fun checksEveryParameterSetIncludingAdditionalInBandSets() {
        H264.validateParameterSets(listOf(sps(80, 45), sps(54, 30)))
        assertThrows(IllegalArgumentException::class.java) {
            H264.validateParameterSets(listOf(sps(80, 45), sps(512, 512)))
        }
        H264.validateFrameSize(1280, 720)
        assertThrows(IllegalArgumentException::class.java) { H264.validateFrameSize(8192, 8192) }
        assertThrows(IllegalArgumentException::class.java) { H264.validateFrameSize(Int.MAX_VALUE, 16) }
        assertThrows(IllegalArgumentException::class.java) { H264.validateFrameSize(0, 720) }
    }

    private fun sps(
        columns: Int,
        mapRows: Int,
        frameOnly: Boolean = true,
        left: Int = 0,
        right: Int = 0,
        top: Int = 0,
        bottom: Int = 0,
    ): ByteArray {
        val bits = StringBuilder("01100111010000101110000000011111")

        fun ue(value: Int) {
            val code = (value.toLong() + 1).toString(2)
            bits.append("0".repeat(code.length - 1)).append(code)
        }
        repeat(5) { ue(0) } // SPS id, frame number, POC type/size, reference frames.
        bits.append('0') // gaps_in_frame_num_value_allowed_flag
        ue(columns - 1)
        ue(mapRows - 1)
        bits.append(if (frameOnly) '1' else '0')
        if (!frameOnly) bits.append('0')
        bits.append("11") // direct_8x8_inference_flag, frame_cropping_flag
        listOf(left, right, top, bottom).forEach(::ue)
        bits.append("01") // VUI absent, rbsp_stop_one_bit.
        while (bits.length % 8 != 0) bits.append('0')
        val output = ByteArrayOutputStream()
        var zeros = 0
        bits.chunked(8).forEach { chunk ->
            val value = chunk.toInt(2)
            if (zeros >= 2 && value <= 3) {
                output.write(3)
                zeros = 0
            }
            output.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return output.toByteArray()
    }
}
