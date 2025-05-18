package com.haishinkit.rtmp.iso

import java.nio.ByteBuffer

// / 7.3.3 Profile, tier and level syntax
internal data class HevcProfileTierLevel(
    val generalProfileSpace: UByte,
    val generalTierFlag: Boolean,
    val generalProfileIdc: UByte,
    val generalProfileCompatFlags: UInt,
    val generalConstraintIndicatorFlags: ULong,
    val generalLevelIdc: UByte,
    val subLayerProfilePresentFlags: List<Boolean>,
    val subLayerLevelPresentFlags: List<Boolean>,
) {
    companion object {
        fun decode(
            buffer: ByteBuffer,
            maxNumberSubLayersMinus1: Int,
        ): HevcProfileTierLevel {
            val isoTypeBuffer = IsoTypeBuffer(buffer)

            val generalProfileSpace = isoTypeBuffer.get(2)
            val generalTierFlag = isoTypeBuffer.boolean
            val generalProfileIdc = isoTypeBuffer.get(5)

            val generalProfileCompatFlag = buffer.getInt().toUInt()
            val generalConstraintIndicatorFlags = isoTypeBuffer.getULong(48)

            val generalLevelIdc = buffer.get()
            val subLayerProfilePresentFlags = mutableListOf<Boolean>()
            val subLayerLevelPresentFlags = mutableListOf<Boolean>()

            for (i in 0 until maxNumberSubLayersMinus1) {
                subLayerProfilePresentFlags.add(isoTypeBuffer.boolean)
                subLayerLevelPresentFlags.add(isoTypeBuffer.boolean)
            }

            if (0 < maxNumberSubLayersMinus1) {
                for (i in maxNumberSubLayersMinus1..8) {
                    isoTypeBuffer.skip(2)
                }
            }

            for (i in 0 until maxNumberSubLayersMinus1) {
                if (subLayerProfilePresentFlags[i]) {
                    isoTypeBuffer.skip(32)
                    isoTypeBuffer.skip(32)
                    isoTypeBuffer.skip(24)
                }

                if (subLayerLevelPresentFlags[i]) {
                    isoTypeBuffer.skip(8)
                }
            }

            return HevcProfileTierLevel(
                generalProfileSpace = generalProfileSpace,
                generalTierFlag = generalTierFlag,
                generalProfileIdc = generalProfileIdc,
                generalProfileCompatFlags = generalProfileCompatFlag,
                generalConstraintIndicatorFlags = generalConstraintIndicatorFlags,
                generalLevelIdc = generalLevelIdc.toUByte(),
                subLayerLevelPresentFlags = subLayerLevelPresentFlags,
                subLayerProfilePresentFlags = subLayerProfilePresentFlags,
            )
        }
    }
}
