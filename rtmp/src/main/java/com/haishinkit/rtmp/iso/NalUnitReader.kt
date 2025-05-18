package com.haishinkit.rtmp.iso

import java.nio.ByteBuffer

internal object NalUnitReader {
    private const val ZERO: Byte = 0
    private const val ONE: Byte = 1

    fun readHevc(buffer: ByteBuffer): List<NalUnit.Hevc> {
        val units = mutableListOf<NalUnit.Hevc>()
        val limit = buffer.limit()
        var lastIndexOf = limit - 1
        val byteArray = buffer.array()
        for (i in limit - 1 downTo 2) {
            if (buffer[i] == ONE && buffer[i - 1] == ZERO && buffer[i - 2] == ZERO) {
                val startCodeLength =
                    if (0 <= i - 3 && buffer[i - 3] == ZERO) {
                        4
                    } else {
                        3
                    }
                val length = lastIndexOf - i
                units.add(
                    NalUnit.Hevc.create(
                        ByteBuffer.allocate(length).apply {
                            put(byteArray, i + 1, length)
                            flip()
                        },
                    ),
                )
                lastIndexOf = i - startCodeLength
            }
        }
        return units
    }

    fun readAvc(buffer: ByteBuffer): List<NalUnit.Avc> {
        val units = mutableListOf<NalUnit.Avc>()
        val limit = buffer.limit()
        var lastIndexOf = limit - 1
        val byteArray = buffer.array()
        for (i in limit - 1 downTo 2) {
            if (buffer[i] == ONE && buffer[i - 1] == ZERO && buffer[i - 2] == ZERO) {
                val startCodeLength =
                    if (0 <= i - 3 && buffer[i - 3] == ZERO) {
                        4
                    } else {
                        3
                    }
                val length = lastIndexOf - i
                units.add(
                    NalUnit.Avc.create(
                        ByteBuffer.allocate(length).apply {
                            put(byteArray, i + 1, length)
                            flip()
                        },
                    ),
                )
                lastIndexOf = i - startCodeLength
            }
        }
        return units
    }
}
