package com.proedit.nle.domain.model

import com.proedit.nle.domain.math.CubicBezierSolver

enum class EasingPreset(
    val p1x: Float,
    val p1y: Float,
    val p2x: Float,
    val p2y: Float,
    val isHold: Boolean = false
) {
    LINEAR(0.25f, 0.25f, 0.75f, 0.75f),
    EASE_IN(0.42f, 0.0f, 1.0f, 1.0f),
    EASE_OUT(0.0f, 0.0f, 0.58f, 1.0f),
    EASE_IN_OUT(0.42f, 0.0f, 0.58f, 1.0f),
    STEEP_S_CURVE(0.85f, 0.0f, 0.15f, 1.0f),
    HOLD(0.0f, 0.0f, 1.0f, 0.0f, isHold = true)
}

data class BezierControlPoints(
    val p1x: Float = 0.25f,
    val p1y: Float = 0.25f,
    val p2x: Float = 0.75f,
    val p2y: Float = 0.75f,
    val isHold: Boolean = false
) {
    init {
        require(p1x in 0f..1f && p2x in 0f..1f) {
            "Bezier X control coordinates must be in [0, 1] for monotonic time evaluation: p1x=$p1x, p2x=$p2x"
        }
    }

    companion object {
        fun fromPreset(preset: EasingPreset): BezierControlPoints = BezierControlPoints(
            p1x = preset.p1x,
            p1y = preset.p1y,
            p2x = preset.p2x,
            p2y = preset.p2y,
            isHold = preset.isHold
        )
    }
}

data class Keyframe<T>(
    val id: String,
    val timeOffsetUs: Long,
    val value: T,
    val easing: BezierControlPoints = BezierControlPoints()
)

data class KeyframeTrack<T>(
    val defaultValue: T,
    val keyframes: List<Keyframe<T>> = emptyList()
) {
    val sortedKeyframes: List<Keyframe<T>>
        get() = keyframes.sortedBy { it.timeOffsetUs }

    fun withUpsertedKeyframe(keyframe: Keyframe<T>): KeyframeTrack<T> {
        val filtered = keyframes.filterNot {
            it.id == keyframe.id || it.timeOffsetUs == keyframe.timeOffsetUs
        }
        return copy(keyframes = (filtered + keyframe).sortedBy { it.timeOffsetUs })
    }

    fun withRemovedKeyframe(keyframeId: String): KeyframeTrack<T> {
        return copy(keyframes = keyframes.filterNot { it.id == keyframeId })
    }
}

fun KeyframeTrack<Float>.evaluateAt(timeOffsetUs: Long): Float {
    val sorted = sortedKeyframes
    if (sorted.isEmpty()) return defaultValue
    if (timeOffsetUs <= sorted.first().timeOffsetUs) return sorted.first().value
    if (timeOffsetUs >= sorted.last().timeOffsetUs) return sorted.last().value

    for (i in 0 until sorted.size - 1) {
        val k0 = sorted[i]
        val k1 = sorted[i + 1]
        if (timeOffsetUs in k0.timeOffsetUs..k1.timeOffsetUs) {
            if (k0.easing.isHold || k1.timeOffsetUs == k0.timeOffsetUs) {
                return k0.value
            }
            val normalizedTime = ((timeOffsetUs - k0.timeOffsetUs).toDouble() /
                (k1.timeOffsetUs - k0.timeOffsetUs).toDouble()).toFloat().coerceIn(0f, 1f)
            val factor = CubicBezierSolver.solve(
                t = normalizedTime,
                x1 = k0.easing.p1x,
                y1 = k0.easing.p1y,
                x2 = k0.easing.p2x,
                y2 = k0.easing.p2y
            )
            return k0.value + (k1.value - k0.value) * factor
        }
    }
    return defaultValue
}

fun KeyframeTrack<Float>.splitAt(splitOffsetUs: Long): Pair<KeyframeTrack<Float>, KeyframeTrack<Float>> {
    if (keyframes.isEmpty()) {
        return this to this
    }
    val valueAtSplit = evaluateAt(splitOffsetUs)
    val leftKeyframes = sortedKeyframes.filter { it.timeOffsetUs < splitOffsetUs } +
        Keyframe(
            id = "kf_split_left_$splitOffsetUs",
            timeOffsetUs = splitOffsetUs,
            value = valueAtSplit
        )
    val rightKeyframes = listOf(
        Keyframe(
            id = "kf_split_right_0_$splitOffsetUs",
            timeOffsetUs = 0L,
            value = valueAtSplit
        )
    ) + sortedKeyframes.filter { it.timeOffsetUs > splitOffsetUs }.map {
        it.copy(timeOffsetUs = (it.timeOffsetUs - splitOffsetUs).coerceAtLeast(0L))
    }
    return KeyframeTrack(defaultValue = defaultValue, keyframes = leftKeyframes) to
        KeyframeTrack(defaultValue = defaultValue, keyframes = rightKeyframes)
}
