package com.proedit.nle.domain.model

data class ClipTransform(
    val positionX: KeyframeTrack<Float> = KeyframeTrack(0f),
    val positionY: KeyframeTrack<Float> = KeyframeTrack(0f),
    val scale: KeyframeTrack<Float> = KeyframeTrack(1.0f),
    val rotation: KeyframeTrack<Float> = KeyframeTrack(0f),
    val opacity: KeyframeTrack<Float> = KeyframeTrack(1.0f)
) {
    fun splitAt(splitOffsetUs: Long): Pair<ClipTransform, ClipTransform> {
        val (posXLeft, posXRight) = positionX.splitAt(splitOffsetUs)
        val (posYLeft, posYRight) = positionY.splitAt(splitOffsetUs)
        val (scaleLeft, scaleRight) = scale.splitAt(splitOffsetUs)
        val (rotLeft, rotRight) = rotation.splitAt(splitOffsetUs)
        val (opLeft, opRight) = opacity.splitAt(splitOffsetUs)
        return ClipTransform(posXLeft, posYLeft, scaleLeft, rotLeft, opLeft) to
            ClipTransform(posXRight, posYRight, scaleRight, rotRight, opRight)
    }

    fun allKeyframeOffsetsUs(): List<Long> = (
        positionX.keyframes.map { it.timeOffsetUs } +
            positionY.keyframes.map { it.timeOffsetUs } +
            scale.keyframes.map { it.timeOffsetUs } +
            rotation.keyframes.map { it.timeOffsetUs } +
            opacity.keyframes.map { it.timeOffsetUs }
        ).distinct().sorted()
}

enum class TransitionType {
    CROSS_DISSOLVE,
    DIP_TO_BLACK,
    WIPE_HORIZONTAL
}

data class TransitionSpec(
    val type: TransitionType = TransitionType.CROSS_DISSOLVE,
    val durationUs: Long = 500_000L
)

enum class TransformProperty {
    POSITION_X,
    POSITION_Y,
    SCALE,
    ROTATION,
    OPACITY,
    SPEED
}

data class Clip(
    val id: String,
    val uri: String,
    val name: String,
    val timelineStartUs: Long,
    val durationUs: Long,
    val sourceStartUs: Long = 0L,
    val sourceMaxDurationUs: Long = 60_000_000L,
    val audioOffsetUs: Long = 0L, // Negative = J-cut (audio leads video), Positive = L-cut (audio trails video)
    val transform: ClipTransform = ClipTransform(),
    val speedCurve: KeyframeTrack<Float> = KeyframeTrack(defaultValue = 1.0f),
    val colorGrade: ColorGrade = ColorGrade(),
    val audioEffects: AudioEffects = AudioEffects(),
    val transitionIn: TransitionSpec? = null,
    val transitionOut: TransitionSpec? = null,
    val proceduralSeed: Int = 0
) {
    val timelineEndUs: Long
        get() = timelineStartUs + durationUs

    val audioTimelineStartUs: Long
        get() = if (audioOffsetUs < 0L) (timelineStartUs + audioOffsetUs).coerceAtLeast(0L) else timelineStartUs

    val audioTimelineEndUs: Long
        get() = if (audioOffsetUs > 0L) timelineEndUs + audioOffsetUs else timelineEndUs

    fun allTimelineKeyframePositionsUs(): List<Long> {
        val offsets = (transform.allKeyframeOffsetsUs() + speedCurve.keyframes.map { it.timeOffsetUs })
            .distinct()
            .filter { it in 0L..durationUs }
        return offsets.map { timelineStartUs + it }.sorted()
    }
}
