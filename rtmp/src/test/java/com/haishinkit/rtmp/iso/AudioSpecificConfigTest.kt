package com.haishinkit.rtmp.iso

import junit.framework.TestCase
import java.nio.ByteBuffer

class AudioSpecificConfigTest : TestCase() {
    fun testEncodeAndDecode() {
        val config =
            AudioSpecificConfig(
                type = AudioSpecificConfig.AudioObjectType.AAC_MAIN,
                frequency = AudioSpecificConfig.SamplingFrequency.HZ44100,
                channel = AudioSpecificConfig.ChannelConfiguration.FRONT_CENTER_AND_FRONT_LEFT_AND_FRONT_RIGHT,
            )
        val buffer = ByteBuffer.allocate(2)
        config.encode(buffer)
        buffer.flip()
        val decodeConfig = AudioSpecificConfig.decode(buffer)
        assertEquals(config, decodeConfig)
    }

    fun testEncodeAndDecode2() {
        val config =
            AudioSpecificConfig(
                type = AudioSpecificConfig.AudioObjectType.AAC_LC,
                frequency = AudioSpecificConfig.SamplingFrequency.HZ48000,
                channel = AudioSpecificConfig.ChannelConfiguration.FRONT_OF_CENTER,
            )
        val buffer = ByteBuffer.allocate(2)
        config.encode(buffer)
        buffer.flip()
        val decodeConfig = AudioSpecificConfig.decode(buffer)
        assertEquals(config, decodeConfig)
    }

    fun testEncodeAndDecode3() {
        val config =
            AudioSpecificConfig(
                type = AudioSpecificConfig.AudioObjectType.AAC_LC,
                frequency = AudioSpecificConfig.SamplingFrequency.HZ48000,
                channel = AudioSpecificConfig.ChannelConfiguration.FRONT_LEFT_AND_FRONT_RIGHT,
            )
        val buffer = ByteBuffer.allocate(2)
        config.encode(buffer)
        buffer.flip()
        val decodeConfig = AudioSpecificConfig.decode(buffer)
        assertEquals(config, decodeConfig)
    }

    fun testEncode() {
        val buffer1 = ByteBuffer.wrap(byteArrayOf(18, 8))
        val config = AudioSpecificConfig.decode(buffer1)
        val buffer2 = ByteBuffer.allocate(2)
        config.encode(buffer2)
        print(buffer2.array().contentToString())
        buffer1.flip()
        buffer2.flip()
        assertEquals(buffer1, buffer2)
    }
}
