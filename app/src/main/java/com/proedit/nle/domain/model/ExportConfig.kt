package com.proedit.nle.domain.model

import androidx.media3.common.MimeTypes

enum class VideoCodecOption(val label: String, val mimeType: String) {
    H264_AVC("H.264 / AVC", MimeTypes.VIDEO_H264),
    H265_HEVC("H.265 / HEVC", MimeTypes.VIDEO_H265)
}

enum class BitrateModeOption(val label: String) {
    VBR("Variable (VBR)"),
    CBR("Constant (CBR)")
}

enum class DynamicRangeOption(val label: String) {
    SDR_REC709("SDR Rec.709"),
    HDR10_TONEMAP_SDR("HDR10 -> SDR Tone-Map"),
    HDR10_PQ("HDR10 PQ Pass-through")
}

data class ExportConfig(
    val resolution: VideoResolution = VideoResolution.FHD_1080P,
    val fps: Double = 24.0,
    val videoCodec: VideoCodecOption = VideoCodecOption.H264_AVC,
    val bitrateMbps: Int = 24,
    val bitrateMode: BitrateModeOption = BitrateModeOption.VBR,
    val dynamicRange: DynamicRangeOption = DynamicRangeOption.SDR_REC709,
    val includeAudio: Boolean = true,
    val outputFileName: String = "Export.mp4"
)
