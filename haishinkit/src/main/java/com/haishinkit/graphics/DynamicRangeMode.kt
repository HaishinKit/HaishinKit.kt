package com.haishinkit.graphics

/** Color pipeline selected before constructing a mixer or playback stream. */
enum class DynamicRangeMode {
    /** Existing 8-bit SDR pipeline. */
    SDR,

    /** 10-bit BT.2020 HLG input/output with floating-point linear-light composition. */
    HLG,
}
