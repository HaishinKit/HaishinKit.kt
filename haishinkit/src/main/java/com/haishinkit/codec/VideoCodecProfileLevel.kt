package com.haishinkit.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * A video codec profile and level used to configure [VideoCodec].
 */
@Suppress("UNUSED")
enum class VideoCodecProfileLevel(
    /**
     * The string value.
     */
    val rawValue: String,
    /**
     * The [MediaFormat] mime type.
     */
    val mime: String,
    /**
     * The [MediaCodec] profile name value.
     */
    val profile: Int,
    /**
     * The [MediaCodec] profile level value.
     */
    val level: Int,
) {
    H264_BASELINE_3_1(
        "H264_Baseline_3_1",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        MediaCodecInfo.CodecProfileLevel.AVCLevel31,
    ),
    H264_BASELINE_3_2(
        "H264_Baseline_3_2",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        MediaCodecInfo.CodecProfileLevel.AVCLevel32,
    ),
    H264_BASELINE_4_0(
        "H264_Baseline_4_0",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        MediaCodecInfo.CodecProfileLevel.AVCLevel4,
    ),
    H264_BASELINE_4_1(
        "H264_Baseline_4_1",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        MediaCodecInfo.CodecProfileLevel.AVCLevel41,
    ),
    H264_BASELINE_4_2(
        "H264_Baseline_4_2",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        MediaCodecInfo.CodecProfileLevel.AVCLevel42,
    ),
    H264_BASELINE_50(
        "H264_Baseline_5_0",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        MediaCodecInfo.CodecProfileLevel.AVCLevel5,
    ),
    H264_BASELINE_51(
        "H264_Baseline_5_1",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        MediaCodecInfo.CodecProfileLevel.AVCLevel51,
    ),
    H264_BASELINE_52(
        "H264_Baseline_5_2",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        MediaCodecInfo.CodecProfileLevel.AVCLevel52,
    ),
    H264_MAIN_3_1(
        "H264_Main_3_1",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.AVCLevel31,
    ),
    H264_MAIN_3_2(
        "H264_Main_3_2",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.AVCLevel32,
    ),
    H264_MAIN_4_0(
        "H264_Main_4_0",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.AVCLevel4,
    ),
    H264_MAIN_4_1(
        "H264_Main_4_1",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.AVCLevel41,
    ),
    H264_MAIN_4_2(
        "H264_Main_4_2",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.AVCLevel42,
    ),
    H264_MAIN_5_0(
        "H264_Main_5_0",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.AVCLevel5,
    ),
    H264_MAIN_5_1(
        "H264_Main_5_1",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.AVCLevel51,
    ),
    H264_MAIN_5_2(
        "H264_Main_5_2",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.AVCLevel52,
    ),
    H264_HIGH_3_1(
        "H264_High_3_1",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        MediaCodecInfo.CodecProfileLevel.AVCLevel31,
    ),
    H264_HIGH_3_2(
        "H264_High_3_2",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        MediaCodecInfo.CodecProfileLevel.AVCLevel32,
    ),
    H264_HIGH_4_0(
        "H264_High_4_0",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        MediaCodecInfo.CodecProfileLevel.AVCLevel4,
    ),
    H264_HIGH_4_1(
        "H264_High_4_1",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        MediaCodecInfo.CodecProfileLevel.AVCLevel41,
    ),
    H264_HIGH_4_2(
        "H264_High_4_2",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        MediaCodecInfo.CodecProfileLevel.AVCLevel42,
    ),
    H264_HIGH_5_0(
        "H264_High_5_0",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        MediaCodecInfo.CodecProfileLevel.AVCLevel5,
    ),
    H264_HIGH_5_1(
        "H264_High_5_1",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        MediaCodecInfo.CodecProfileLevel.AVCLevel51,
    ),
    H264_HIGH_5_2(
        "H264_High_5_2",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        MediaCodecInfo.CodecProfileLevel.AVCLevel52,
    ),

    HEVC_MAIN_1(
        "HEVC_Main_1",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1,
    ),
    HEVC_MAIN_2(
        "HEVC_Main_2",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2,
    ),
    HEVC_MAIN_2_1(
        "HEVC_Main_2_1",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21,
    ),
    HEVC_MAIN_3(
        "HEVC_Main_3",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3,
    ),
    HEVC_MAIN_3_1(
        "HEVC_Main_3_1",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31,
    ),
    HEVC_MAIN_4(
        "HEVC_Main_4",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4,
    ),
    HEVC_MAIN_4_1(
        "HEVC_Main_4_1",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41,
    ),
    HEVC_MAIN_5(
        "HEVC_Main_5",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5,
    ),
    HEVC_MAIN_5_1(
        "HEVC_Main_5_1",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51,
    ),
    HEVC_MAIN_5_2(
        "HEVC_Main_5_2",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52,
    ),
    HEVC_MAIN_6(
        "HEVC_Main_6",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6,
    ),
    HEVC_MAIN_6_1(
        "HEVC_Main_6_1",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61,
    ),
    HEVC_MAIN_6_2(
        "HEVC_Main_6_2",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_1(
        "VP9_Profile0_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level1,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_11(
        "VP9_Profile0_1_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level11,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_2(
        "VP9_Profile0_2",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level2,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_21(
        "VP9_Profile0_2_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level21,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_3(
        "VP9_Profile0_3",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level3,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_31(
        "VP9_Profile0_3_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level31,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_4(
        "VP9_Profile0_4",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level4,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_41(
        "VP9_Profile0_4_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level41,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_5(
        "VP9_Profile0_5",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level5,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_51(
        "VP9_Profile0_5_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level51,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_52(
        "VP9_Profile0_5_2",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level52,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_6(
        "VP9_Profile0_6",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level6,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_61(
        "VP9_Profile0_6_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level61,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE0_62(
        "VP9_Profile0_6_2",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level62,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE1_1(
        "VP9_Profile1_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile1,
        MediaCodecInfo.CodecProfileLevel.VP9Level1,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE1_11(
        "VP9_Profile1_1_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile1,
        MediaCodecInfo.CodecProfileLevel.VP9Level11,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE1_2(
        "VP9_Profile1_2",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile1,
        MediaCodecInfo.CodecProfileLevel.VP9Level2,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE1_21(
        "VP9_Profile1_2_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile1,
        MediaCodecInfo.CodecProfileLevel.VP9Level21,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE1_3(
        "VP9_Profile1_3",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile1,
        MediaCodecInfo.CodecProfileLevel.VP9Level3,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE1_31(
        "VP9_Profile1_3_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile1,
        MediaCodecInfo.CodecProfileLevel.VP9Level31,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE1_4(
        "VP9_Profile1_4",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile1,
        MediaCodecInfo.CodecProfileLevel.VP9Level4,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE1_41(
        "VP9_Profile1_4_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile1,
        MediaCodecInfo.CodecProfileLevel.VP9Level41,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE1_5(
        "VP9_Profile1_5",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile1,
        MediaCodecInfo.CodecProfileLevel.VP9Level5,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE1_51(
        "VP9_Profile1_5_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile1,
        MediaCodecInfo.CodecProfileLevel.VP9Level51,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE1_6(
        "VP9_Profile1_6",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile0,
        MediaCodecInfo.CodecProfileLevel.VP9Level6,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE1_61(
        "VP9_Profile1_6_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile1,
        MediaCodecInfo.CodecProfileLevel.VP9Level61,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE1_62(
        "VP9_Profile1_6_2",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile1,
        MediaCodecInfo.CodecProfileLevel.VP9Level62,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE2_1(
        "VP9_Profile2_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level1,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE2_11(
        "VP9_Profile2_1_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level11,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE2_2(
        "VP9_Profile2_2",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level2,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE2_21(
        "VP9_Profile2_2_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level21,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE2_3(
        "VP9_Profile2_3",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level3,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE2_31(
        "VP9_Profile2_3_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level31,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE2_4(
        "VP9_Profile2_4",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level4,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE2_41(
        "VP9_Profile2_4_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level41,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE2_5(
        "VP9_Profile2_5",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level5,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE2_51(
        "VP9_Profile2_5_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level51,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE2_6(
        "VP9_Profile2_6",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level6,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE2_61(
        "VP9_Profile2_6_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level61,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE2_62(
        "VP9_Profile2_6_2",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level62,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE3_1(
        "VP9_Profile3_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3,
        MediaCodecInfo.CodecProfileLevel.VP9Level1,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE3_11(
        "VP9_Profile3_1_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3,
        MediaCodecInfo.CodecProfileLevel.VP9Level11,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE3_2(
        "VP9_Profile3_2",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3,
        MediaCodecInfo.CodecProfileLevel.VP9Level2,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE3_21(
        "VP9_Profile3_2_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3,
        MediaCodecInfo.CodecProfileLevel.VP9Level21,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE3_3(
        "VP9_Profile3_3",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3,
        MediaCodecInfo.CodecProfileLevel.VP9Level3,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE3_31(
        "VP9_Profile3_3_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3,
        MediaCodecInfo.CodecProfileLevel.VP9Level31,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE3_4(
        "VP9_Profile3_4",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3,
        MediaCodecInfo.CodecProfileLevel.VP9Level4,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE3_41(
        "VP9_Profile3_4_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3,
        MediaCodecInfo.CodecProfileLevel.VP9Level41,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE3_5(
        "VP9_Profile3_5",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3,
        MediaCodecInfo.CodecProfileLevel.VP9Level5,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE3_51(
        "VP9_Profile3_5_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3,
        MediaCodecInfo.CodecProfileLevel.VP9Level51,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE3_6(
        "VP9_Profile3_6",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3,
        MediaCodecInfo.CodecProfileLevel.VP9Level6,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE3_61(
        "VP9_Profile3_6_1",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3,
        MediaCodecInfo.CodecProfileLevel.VP9Level61,
    ),

    @RequiresApi(Build.VERSION_CODES.N)
    VP9_PROFILE3_62(
        "VP9_Profile3_6_2",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3,
        MediaCodecInfo.CodecProfileLevel.VP9Level62,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_2(
        "AV1_Main8_2",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level2,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_2_1(
        "AV1_Main8_2_1",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level21,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_2_2(
        "AV1_Main8_2_2",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level22,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_2_3(
        "AV1_Main8_2_3",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level23,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_3(
        "AV1_Main8_3",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level3,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_3_1(
        "AV1_Main8_3_1",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level31,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_3_2(
        "AV1_Main8_3_2",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level32,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_3_3(
        "AV1_Main8_3_3",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level33,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_4(
        "AV1_Main8_4",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level4,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_4_1(
        "AV1_Main8_4_1",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level41,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_4_2(
        "AV1_Main8_4_2",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level42,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_4_3(
        "AV1_Main8_4_3",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level43,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_5(
        "AV1_Main8_5",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level5,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_5_1(
        "AV1_Main8_5_1",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level51,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_5_2(
        "AV1_Main8_5_2",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level52,
    ),

    @RequiresApi(Build.VERSION_CODES.Q)
    AV1_MAIN8_5_3(
        "AV1_Main8_5_3",
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level53,
    ),
}
