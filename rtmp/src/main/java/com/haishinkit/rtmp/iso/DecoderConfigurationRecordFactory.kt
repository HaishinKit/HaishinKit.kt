package com.haishinkit.rtmp.iso

import android.media.MediaFormat
import java.nio.ByteBuffer

internal interface DecoderConfigurationRecordFactory {
    fun create(mediaFormat: MediaFormat): DecoderConfigurationRecord

    fun decode(buffer: ByteBuffer): DecoderConfigurationRecord
}
