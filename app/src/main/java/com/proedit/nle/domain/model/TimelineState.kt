package com.proedit.nle.domain.model

enum class EditMode(val label: String, val shortCode: String) {
    RIPPLE("Ripple", "RPL"),
    ROLL("Roll", "ROL"),
    SLIP("Slip", "SLP"),
    SLIDE("Slide", "SLD"),
    BLADE("Blade", "BLD")
}

data class VideoResolution(
    val width: Int,
    val height: Int,
    val label: String
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 16f / 9f

    companion object {
        val HD_720P = VideoResolution(1280, 720, "720p 16:9")
        val FHD_1080P = VideoResolution(1920, 1080, "1080p 16:9")
        val UHD_4K = VideoResolution(3840, 2160, "4K 16:9")
        val VERTICAL_1080P = VideoResolution(1080, 1920, "1080p 9:16")
        val SQUARE_1080P = VideoResolution(1080, 1080, "1080p 1:1")

        val PRESETS = listOf(FHD_1080P, VERTICAL_1080P, SQUARE_1080P, UHD_4K, HD_720P)
    }
}

data class TimelineMarker(
    val id: String,
    val timestampUs: Long,
    val label: String,
    val colorHex: Long = 0xFFF59E0B
)

data class TimelineState(
    val id: String = "project_default",
    val name: String = "Untitled Project",
    val fps: Double = 30.0,
    val isDropFrame: Boolean = false,
    val resolution: VideoResolution = VideoResolution.FHD_1080P,
    val tracks: List<Track> = emptyList(),
    val playheadPositionUs: Long = 0L,
    val zoomPixelsPerSecond: Float = 100f,
    val isMagneticSnapEnabled: Boolean = true,
    val selectedClipId: String? = null,
    val selectedTrackId: String? = null,
    val activeEditMode: EditMode = EditMode.RIPPLE,
    val markers: List<TimelineMarker> = emptyList()
) {
    val totalDurationUs: Long
        get() = tracks.flatMap { it.clips }
            .maxOfOrNull { maxOf(it.timelineEndUs, it.audioTimelineEndUs) } ?: 0L

    val scrubBoundsUs: Long
        get() = totalDurationUs.coerceAtLeast(10_000_000L)

    val totalClipCount: Int
        get() = tracks.sumOf { it.clips.size }

    fun findClip(clipId: String?): Clip? {
        if (clipId == null) return null
        for (track in tracks) {
            val match = track.clips.find { it.id == clipId }
            if (match != null) return match
        }
        return null
    }

    fun findTrackForClip(clipId: String?): Track? {
        if (clipId == null) return null
        return tracks.find { track -> track.clips.any { it.id == clipId } }
    }

    fun activeVideoClipAt(timestampUs: Long): Clip? {
        val videoTracks = tracks.filter { it.type == TrackType.VIDEO && !it.isMuted }
        for (track in videoTracks) {
            val clip = track.clips.find { timestampUs >= it.timelineStartUs && timestampUs < it.timelineEndUs }
            if (clip != null) return clip
        }
        return null
    }

    fun activeAudioClipsAt(timestampUs: Long): List<Pair<Track, Clip>> {
        val result = mutableListOf<Pair<Track, Clip>>()
        for (track in tracks) {
            if (track.type != TrackType.AUDIO || track.isMuted) continue
            val clip = track.clips.find {
                timestampUs >= it.audioTimelineStartUs && timestampUs < it.audioTimelineEndUs
            }
            if (clip != null) {
                result.add(track to clip)
            }
        }
        return result
    }

    companion object {
        val SUPPORTED_FPS = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 60.0)

        fun createEmptyProjectTimeline(
            id: String,
            name: String,
            resolution: VideoResolution,
            fps: Double
        ): TimelineState = TimelineState(
            id = id,
            name = name,
            fps = fps,
            isDropFrame = (fps == 29.97),
            resolution = resolution,
            tracks = listOf(
                Track(id = "track_v2", name = "V2", type = TrackType.VIDEO),
                Track(id = "track_v1", name = "V1", type = TrackType.VIDEO),
                Track(id = "track_a1", name = "A1", type = TrackType.AUDIO)
            ),
            playheadPositionUs = 0L,
            zoomPixelsPerSecond = 96f,
            isMagneticSnapEnabled = true,
            activeEditMode = EditMode.RIPPLE
        )
    }
}
