package com.proedit.nle.domain.math

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object TimecodeCalculator {

    private const val MICROSECONDS_PER_SECOND = 1_000_000L

    /**
     * Returns exact frame duration in microseconds: round(1_000_000.0 / fps)
     */
    fun frameDurationUs(fps: Double): Long {
        require(fps > 0.0) { "FPS must be positive, got $fps" }
        return (1_000_000.0 / fps).roundToLong()
    }

    /**
     * Rounds an arbitrary timestamp in microseconds to the nearest exact frame boundary.
     */
    fun quantizeToFrameBoundaryUs(timeUs: Long, fps: Double): Long {
        val frameUs = frameDurationUs(fps)
        if (frameUs <= 0L) return timeUs
        val frameIndex = (timeUs.toDouble() / frameUs.toDouble()).roundToLong()
        return (frameIndex * frameUs).coerceAtLeast(0L)
    }

    /**
     * Converts microseconds to SMPTE timecode string (HH:MM:SS:FF or HH:MM:SS;FF for drop-frame).
     */
    fun formatSmpte(
        timeUs: Long,
        fps: Double,
        dropFrame: Boolean = false
    ): String {
        val clampedUs = timeUs.coerceAtLeast(0L)
        val nominalFps = nominalFrameRate(fps)
        val frameUs = frameDurationUs(fps)
        var totalFrames = ((clampedUs + frameUs / 2) / frameUs).coerceAtLeast(0L)

        val isDf = dropFrame && (abs(fps - 29.97) < 0.05 || abs(fps - 59.94) < 0.05)
        if (isDf) {
            val dropFrames = if (nominalFps == 60L) 4L else 2L
            val framesPerHour = nominalFps * 3600L
            val framesPer24Hours = framesPerHour * 24L
            val framesPer10Minutes = nominalFps * 600L - dropFrames * 9L
            val framesPerMinute = nominalFps * 60L - dropFrames

            totalFrames %= framesPer24Hours
            val d = totalFrames / framesPer10Minutes
            val m = totalFrames % framesPer10Minutes

            if (m > dropFrames) {
                totalFrames += dropFrames * 9L * d + dropFrames * ((m - dropFrames) / framesPerMinute)
            } else {
                totalFrames += dropFrames * 9L * d
            }
        }

        val ff = (totalFrames % nominalFps).toInt()
        val totalSeconds = totalFrames / nominalFps
        val ss = (totalSeconds % 60L).toInt()
        val mm = ((totalSeconds / 60L) % 60L).toInt()
        val hh = (totalSeconds / 3600L).toInt()
        val separator = if (isDf) ';' else ':'

        return String.format(Locale.US, "%02d:%02d:%02d%c%02d", hh, mm, ss, separator, ff)
    }

    /**
     * Parses a SMPTE string (HH:MM:SS:FF or HH:MM:SS;FF) into microseconds.
     */
    fun parseSmpte(
        smpte: String,
        fps: Double
    ): Long {
        val cleaned = smpte.trim()
        val parts = cleaned.split(':', ';')
        require(parts.size == 4) { "Invalid SMPTE format '$smpte'; expected HH:MM:SS:FF" }

        val hh = parts[0].toLongOrNull() ?: throw IllegalArgumentException("Invalid hours in $smpte")
        val mm = parts[1].toLongOrNull() ?: throw IllegalArgumentException("Invalid minutes in $smpte")
        val ss = parts[2].toLongOrNull() ?: throw IllegalArgumentException("Invalid seconds in $smpte")
        val ff = parts[3].toLongOrNull() ?: throw IllegalArgumentException("Invalid frames in $smpte")

        val nominalFps = nominalFrameRate(fps)
        require(mm in 0..59 && ss in 0..59 && ff in 0 until nominalFps) {
            "Out-of-range SMPTE component in '$smpte' for nominal FPS $nominalFps"
        }

        val isDropFrame = cleaned.contains(';') && (abs(fps - 29.97) < 0.05 || abs(fps - 59.94) < 0.05)
        val totalMinutes = 60L * hh + mm
        val droppedFrames = if (isDropFrame) {
            val dropCount = if (nominalFps == 60L) 4L else 2L
            dropCount * (totalMinutes - totalMinutes / 10L)
        } else {
            0L
        }

        val totalFrames = (hh * 3600L + mm * 60L + ss) * nominalFps + ff - droppedFrames
        val frameUs = frameDurationUs(fps)
        return totalFrames * frameUs
    }

    /**
     * Converts timeline timestamp (us) to canvas horizontal coordinate (px).
     */
    fun timeUsToPixelX(
        timeUs: Long,
        zoomPixelsPerSecond: Float,
        scrollOffsetPx: Float = 0f
    ): Float {
        val seconds = timeUs.toDouble() / MICROSECONDS_PER_SECOND.toDouble()
        return (seconds * zoomPixelsPerSecond.toDouble() - scrollOffsetPx.toDouble()).toFloat()
    }

    /**
     * Converts canvas horizontal coordinate (px) to timeline timestamp (us).
     */
    fun pixelXToTimeUs(
        pixelX: Float,
        zoomPixelsPerSecond: Float,
        scrollOffsetPx: Float = 0f
    ): Long {
        require(zoomPixelsPerSecond > 0f) { "zoomPixelsPerSecond must be > 0, got $zoomPixelsPerSecond" }
        val totalPixels = pixelX.toDouble() + scrollOffsetPx.toDouble()
        val seconds = totalPixels / zoomPixelsPerSecond.toDouble()
        return (seconds * MICROSECONDS_PER_SECOND.toDouble()).roundToLong().coerceAtLeast(0L)
    }

    fun nominalFrameRate(fps: Double): Long = when {
        abs(fps - 23.976) < 0.05 -> 24L
        abs(fps - 29.97) < 0.05 -> 30L
        abs(fps - 59.94) < 0.05 -> 60L
        else -> fps.roundToLong().coerceAtLeast(1L)
    }
}
