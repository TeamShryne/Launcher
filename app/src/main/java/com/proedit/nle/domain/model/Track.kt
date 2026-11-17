package com.proedit.nle.domain.model

enum class TrackType {
    VIDEO,
    AUDIO,
    ADJUSTMENT
}

data class Track(
    val id: String,
    val name: String,
    val type: TrackType,
    val clips: List<Clip> = emptyList(),
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val isLocked: Boolean = false,
    val volumeDb: Float = 0.0f
) {
    val sortedClips: List<Clip>
        get() = clips.sortedBy { it.timelineStartUs }
}
