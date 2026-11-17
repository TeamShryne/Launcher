package com.proedit.nle.ui.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.proedit.nle.domain.model.AudioEffects
import com.proedit.nle.domain.model.EqBand
import com.proedit.nle.ui.theme.AccentPrimary
import com.proedit.nle.ui.theme.AccentSecondary
import com.proedit.nle.ui.theme.BackgroundPrimary
import com.proedit.nle.ui.theme.BorderSubtle
import com.proedit.nle.ui.theme.SemanticGreen
import com.proedit.nle.ui.theme.SemanticRed
import com.proedit.nle.ui.theme.SurfaceDark
import com.proedit.nle.ui.theme.SurfaceElevated
import com.proedit.nle.ui.theme.TextPrimary
import com.proedit.nle.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

@Composable
fun AudioEqPanel(
    audioEffects: AudioEffects,
    audioOffsetUs: Long,
    onUpdateAudioEffects: (AudioEffects) -> Unit,
    onUpdateAudioOffsetUs: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var selectedBandIndex by remember { mutableStateOf(2) } // Default to 1kHz Peak 2

    val minFreq = 20f
    val maxFreq = 20000f
    val logSpan = ln(maxFreq / minFreq)

    fun freqToNormalizedX(freqHz: Float): Float {
        return (ln(freqHz.coerceIn(minFreq, maxFreq) / minFreq) / logSpan).coerceIn(0f, 1f)
    }

    fun normalizedXToFreq(normX: Float): Float {
        return (minFreq * (maxFreq / minFreq).toDouble().pow(normX.coerceIn(0f, 1f).toDouble())).toFloat()
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("audio_eq_panel"),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Left Column: Master Gain, Voice Isolation, Limiter, and J/L Split Readout
        Column(
            modifier = Modifier
                .width(148.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "5-BAND PARAMETRIC EQ",
                    style = MaterialTheme.typography.titleSmall,
                    color = AccentSecondary
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "CLIP GAIN: %+.1f dBFS",
                        audioEffects.volumeDb
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (audioEffects.volumeDb > 0f) SemanticRed else TextPrimary
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "J/L SPLIT: %+.2f s",
                        audioOffsetUs / 1_000_000.0
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (audioOffsetUs != 0L) AccentPrimary else TextSecondary
                )
            }

            // Voice Isolation & Brickwall Limiter Toggles
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (audioEffects.voiceIsolationEnabled) AccentSecondary.copy(alpha = 0.22f) else SurfaceElevated,
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            1.dp,
                            if (audioEffects.voiceIsolationEnabled) AccentSecondary else BorderSubtle,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onUpdateAudioEffects(
                                audioEffects.copy(voiceIsolationEnabled = !audioEffects.voiceIsolationEnabled)
                            )
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("voice_isolation_toggle")
                ) {
                    Text(
                        text = if (audioEffects.voiceIsolationEnabled) "VOICE ISO: ACTIVE" else "VOICE ISO: OFF",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = if (audioEffects.voiceIsolationEnabled) AccentSecondary else TextSecondary
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (audioEffects.limiterEnabled) SemanticGreen.copy(alpha = 0.18f) else SurfaceElevated,
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            1.dp,
                            if (audioEffects.limiterEnabled) SemanticGreen else BorderSubtle,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onUpdateAudioEffects(
                                audioEffects.copy(limiterEnabled = !audioEffects.limiterEnabled)
                            )
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("limiter_toggle")
                ) {
                    Text(
                        text = if (audioEffects.limiterEnabled) "LIMITER: -0.3 dBFS" else "LIMITER: BYPASSED",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = if (audioEffects.limiterEnabled) SemanticGreen else TextSecondary
                    )
                }
            }
        }

        // Center Interactive 5-Band Parametric EQ Biquad Response Graph
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(BackgroundPrimary, RoundedCornerShape(6.dp))
                .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                .padding(8.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("audio_eq_canvas")
                    .pointerInput(audioEffects.eqBands) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                var closestIdx = 0
                                var minDist = Float.MAX_VALUE
                                audioEffects.eqBands.forEachIndexed { index, band ->
                                    val bx = freqToNormalizedX(band.frequencyHz) * w
                                    val by = (0.5f - (band.gainDb / 36f)) * h
                                    val dist = hypot(pos.x - bx, pos.y - by)
                                    if (dist < minDist) {
                                        minDist = dist
                                        closestIdx = index
                                    }
                                }
                                selectedBandIndex = closestIdx
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val normX = (change.position.x / w).coerceIn(0.02f, 0.98f)
                                val newFreq = normalizedXToFreq(normX)
                                val rawGain = ((0.5f - (change.position.y / h)) * 36f).coerceIn(-18f, 18f)
                                val snappedGain = if (abs(rawGain) < 0.45f) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    0.0f
                                } else {
                                    rawGain
                                }

                                val updatedBands = audioEffects.eqBands.mapIndexed { idx, band ->
                                    if (idx == selectedBandIndex) {
                                        band.copy(frequencyHz = newFreq, gainDb = snappedGain)
                                    } else {
                                        band
                                    }
                                }
                                onUpdateAudioEffects(audioEffects.copy(eqBands = updatedBands))
                            }
                        )
                    }
            ) {
                val w = size.width
                val h = size.height

                // Logarithmic Frequency Grid (100Hz, 1kHz, 10kHz) & 0dB Unity Center Line
                val gridFreqs = listOf(60f, 250f, 1000f, 4000f, 12000f)
                for (gf in gridFreqs) {
                    val x = freqToNormalizedX(gf) * w
                    drawLine(BorderSubtle, Offset(x, 0f), Offset(x, h), 1f)
                }
                drawLine(BorderSubtle, Offset(0f, h * 0.25f), Offset(w, h * 0.25f), 1f)
                drawLine(TextSecondary.copy(alpha = 0.45f), Offset(0f, h * 0.5f), Offset(w, h * 0.5f), 1.5f)
                drawLine(BorderSubtle, Offset(0f, h * 0.75f), Offset(w, h * 0.75f), 1f)

                // Draw Biquad Frequency Response Curve
                val responsePath = Path()
                val fillPath = Path()
                val numPoints = 96
                for (i in 0..numPoints) {
                    val normX = i.toFloat() / numPoints.toFloat()
                    val freq = normalizedXToFreq(normX)
                    val db = audioEffects.evaluateMagnitudeDbAt(freq)
                    val px = normX * w
                    val py = ((0.5f - (db / 36f)).coerceIn(0.02f, 0.98f)) * h
                    if (i == 0) {
                        responsePath.moveTo(px, py)
                        fillPath.moveTo(px, h * 0.5f)
                        fillPath.lineTo(px, py)
                    } else {
                        responsePath.lineTo(px, py)
                        fillPath.lineTo(px, py)
                    }
                }
                fillPath.lineTo(w, h * 0.5f)
                fillPath.close()

                drawPath(fillPath, color = AccentSecondary.copy(alpha = 0.16f))
                drawPath(responsePath, color = AccentSecondary, style = Stroke(width = 2.4f))

                // Draw Draggable Band Nodes
                audioEffects.eqBands.forEachIndexed { idx, band ->
                    val bx = freqToNormalizedX(band.frequencyHz) * w
                    val by = ((0.5f - (band.gainDb / 36f)).coerceIn(0.04f, 0.96f)) * h
                    val isSelected = idx == selectedBandIndex
                    drawCircle(
                        color = if (isSelected) AccentPrimary else AccentSecondary,
                        radius = if (isSelected) 6.5f else 5.0f,
                        center = Offset(bx, by)
                    )
                    drawCircle(
                        color = BackgroundPrimary,
                        radius = 2.2f,
                        center = Offset(bx, by)
                    )
                }
            }
        }

        // Right Column: Selected Band Readout, Q-Factor & J/L Split Quick Nudge
        val activeBand: EqBand = audioEffects.eqBands.getOrElse(selectedBandIndex) {
            audioEffects.eqBands.first()
        }
        Column(
            modifier = Modifier
                .width(132.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = activeBand.type.name.replace('_', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentPrimary
                )
                Text(
                    text = String.format(Locale.US, "FREQ: %.0f Hz", activeBand.frequencyHz),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary
                )
                Text(
                    text = String.format(Locale.US, "GAIN: %+.1f dB", activeBand.gainDb),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary
                )
                Text(
                    text = String.format(Locale.US, "Q-FACTOR: %.2f", activeBand.qFactor),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            // J-Cut / L-Cut Quick Split Controls
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(SurfaceElevated, RoundedCornerShape(4.dp))
                            .border(0.5.dp, BorderSubtle, RoundedCornerShape(4.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onUpdateAudioOffsetUs(audioOffsetUs - 250_000L) // -0.25s J-Cut
                            }
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("J-CUT -6F", style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp), color = AccentPrimary)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(SurfaceElevated, RoundedCornerShape(4.dp))
                            .border(0.5.dp, BorderSubtle, RoundedCornerShape(4.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onUpdateAudioOffsetUs(audioOffsetUs + 250_000L) // +0.25s L-Cut
                            }
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("L-CUT +6F", style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp), color = AccentSecondary)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceElevated, RoundedCornerShape(4.dp))
                        .border(0.5.dp, BorderSubtle, RoundedCornerShape(4.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onUpdateAudioEffects(AudioEffects())
                            onUpdateAudioOffsetUs(0L)
                        }
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("FLAT 0.0 dB", style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp), color = TextSecondary)
                }
            }
        }
    }
}
