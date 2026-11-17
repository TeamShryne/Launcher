package com.proedit.nle.ui.curves

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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.proedit.nle.domain.math.CubicBezierSolver
import com.proedit.nle.domain.math.SpeedRampIntegrator
import com.proedit.nle.domain.model.BezierControlPoints
import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.EasingPreset
import com.proedit.nle.domain.model.Keyframe
import com.proedit.nle.domain.model.TransformProperty
import com.proedit.nle.ui.theme.AccentPrimary
import com.proedit.nle.ui.theme.AccentSecondary
import com.proedit.nle.ui.theme.BackgroundPrimary
import com.proedit.nle.ui.theme.BorderSubtle
import com.proedit.nle.ui.theme.SurfaceDark
import com.proedit.nle.ui.theme.SurfaceElevated
import com.proedit.nle.ui.theme.TextPrimary
import com.proedit.nle.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.hypot

@Composable
fun BezierGraphEditor(
    selectedClip: Clip?,
    activeProperty: TransformProperty,
    onSelectProperty: (TransformProperty) -> Unit,
    onUpdateBezierPoints: (BezierControlPoints) -> Unit,
    onApplySpeedPreset: (Float, Float, BezierControlPoints) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var controlPoints by remember(selectedClip?.id) {
        val existing = selectedClip?.speedCurve?.keyframes?.firstOrNull()?.easing ?: BezierControlPoints()
        mutableStateOf(existing)
    }
    var activeHandle by remember { mutableStateOf<Int?>(null) }

    val avgSpeed = remember(selectedClip, controlPoints) {
        if (selectedClip != null) {
            SpeedRampIntegrator.averageSpeedMultiplier(selectedClip.durationUs, selectedClip.speedCurve)
        } else 1.0f
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("bezier_graph_editor"),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Left Column: Property Selector & Presets
        Column(
            modifier = Modifier
                .width(142.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "CURVE & SPEED RAMP",
                    style = MaterialTheme.typography.titleSmall,
                    color = AccentPrimary
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "P1(%.2f, %.2f) P2(%.2f, %.2f)",
                        controlPoints.p1x,
                        controlPoints.p1y,
                        controlPoints.p2x,
                        controlPoints.p2y
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = String.format(Locale.US, "AVG RATE: %.2fx", avgSpeed),
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentSecondary
                )
            }

            // Easing Preset Buttons
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                EasingPreset.entries.take(5).forEach { preset ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceElevated, RoundedCornerShape(4.dp))
                            .border(0.5.dp, BorderSubtle, RoundedCornerShape(4.dp))
                            .clickable {
                                val next = BezierControlPoints.fromPreset(preset)
                                controlPoints = next
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onUpdateBezierPoints(next)
                            }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .testTag("bezier_preset_${preset.name.lowercase()}")
                    ) {
                        Text(
                            text = preset.name.replace('_', ' '),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        // Center Interactive Normalized Cubic Bezier & Velocity Derivative Canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(BackgroundPrimary, RoundedCornerShape(6.dp))
                .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                .padding(12.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("bezier_curve_canvas")
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val p1Screen = Offset(controlPoints.p1x * w, (1f - controlPoints.p1y) * h)
                                val p2Screen = Offset(controlPoints.p2x * w, (1f - controlPoints.p2y) * h)
                                val d1 = hypot(offset.x - p1Screen.x, offset.y - p1Screen.y)
                                val d2 = hypot(offset.x - p2Screen.x, offset.y - p2Screen.y)
                                activeHandle = if (d1 <= d2) 1 else 2
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val nx = (change.position.x / w).coerceIn(0f, 1f)
                                val ny = (1f - (change.position.y / h)).coerceIn(-0.25f, 1.25f)

                                controlPoints = if (activeHandle == 1) {
                                    controlPoints.copy(p1x = nx, p1y = ny, isHold = false)
                                } else {
                                    controlPoints.copy(p2x = nx, p2y = ny, isHold = false)
                                }
                                onUpdateBezierPoints(controlPoints)
                            },
                            onDragEnd = {
                                activeHandle = null
                            }
                        )
                    }
            ) {
                val w = size.width
                val h = size.height

                // Normalized 4x4 Grid
                for (i in 0..4) {
                    val f = i / 4f
                    drawLine(
                        color = BorderSubtle,
                        start = Offset(w * f, 0f),
                        end = Offset(w * f, h),
                        strokeWidth = 1f
                    )
                    drawLine(
                        color = BorderSubtle,
                        start = Offset(0f, h * f),
                        end = Offset(w, h * f),
                        strokeWidth = 1f
                    )
                }

                // Diagonal Unity Reference Line
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                drawLine(
                    color = TextSecondary.copy(alpha = 0.35f),
                    start = Offset(0f, h),
                    end = Offset(w, 0f),
                    strokeWidth = 1f,
                    pathEffect = dashEffect
                )

                // Velocity Derivative Curve dBy/dt (Technical Blue)
                val velocityPath = Path()
                val samples = 64
                for (i in 0..samples) {
                    val t = i.toFloat() / samples.toFloat()
                    val vel = if (controlPoints.isHold) 0f else CubicBezierSolver.velocityDerivative(
                        t = t,
                        x1 = controlPoints.p1x,
                        y1 = controlPoints.p1y,
                        x2 = controlPoints.p2x,
                        y2 = controlPoints.p2y
                    )
                    val normVel = (vel / 3.0f).coerceIn(0f, 1f)
                    val px = t * w
                    val py = (1f - normVel) * h
                    if (i == 0) velocityPath.moveTo(px, py) else velocityPath.lineTo(px, py)
                }
                drawPath(
                    path = velocityPath,
                    color = AccentSecondary.copy(alpha = 0.45f),
                    style = Stroke(width = 1.5f)
                )

                // Cubic Bezier Easing Curve By(t) (Cinema Amber)
                val bezierPath = Path()
                for (i in 0..samples) {
                    val t = i.toFloat() / samples.toFloat()
                    val by = if (controlPoints.isHold) {
                        if (t < 1f) 0f else 1f
                    } else {
                        CubicBezierSolver.solve(
                            t = t,
                            x1 = controlPoints.p1x,
                            y1 = controlPoints.p1y,
                            x2 = controlPoints.p2x,
                            y2 = controlPoints.p2y
                        )
                    }
                    val px = t * w
                    val py = (1f - by.coerceIn(-0.1f, 1.1f)) * h
                    if (i == 0) bezierPath.moveTo(px, py) else bezierPath.lineTo(px, py)
                }
                drawPath(
                    path = bezierPath,
                    color = AccentPrimary,
                    style = Stroke(width = 2.8f)
                )

                // Tangent Handle Arms (P0 -> P1 and P3 -> P2)
                val p0 = Offset(0f, h)
                val p3 = Offset(w, 0f)
                val p1 = Offset(controlPoints.p1x * w, (1f - controlPoints.p1y).coerceIn(0f, 1f) * h)
                val p2 = Offset(controlPoints.p2x * w, (1f - controlPoints.p2y).coerceIn(0f, 1f) * h)

                drawLine(color = AccentPrimary.copy(alpha = 0.7f), start = p0, end = p1, strokeWidth = 1.6f)
                drawLine(color = AccentSecondary.copy(alpha = 0.7f), start = p3, end = p2, strokeWidth = 1.6f)

                drawCircle(color = AccentPrimary, radius = 6.5f, center = p1)
                drawCircle(color = BackgroundPrimary, radius = 2.5f, center = p1)
                drawCircle(color = AccentSecondary, radius = 6.5f, center = p2)
                drawCircle(color = BackgroundPrimary, radius = 2.5f, center = p2)
            }
        }

        // Right Column: Speed Ramp Quick Ramps & Property Switcher
        Column(
            modifier = Modifier
                .width(126.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "SPEED RAMPS",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                val ramps = listOf(
                    Triple("1.0x UNITY", 1.0f, 1.0f),
                    Triple("2.0x FAST", 2.0f, 2.0f),
                    Triple("0.5x SLOW-MO", 0.5f, 0.5f),
                    Triple("1x -> 3x RAMP", 1.0f, 3.0f),
                    Triple("3x -> 0.5x DROP", 3.0f, 0.5f)
                )
                ramps.forEach { (label, startV, endV) ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceElevated, RoundedCornerShape(4.dp))
                            .border(0.5.dp, BorderSubtle, RoundedCornerShape(4.dp))
                            .clickable {
                                if (startV == 1.0f && endV == 1.0f) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                onApplySpeedPreset(startV, endV, controlPoints)
                            }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (startV == 1.0f && endV == 1.0f) AccentPrimary else TextPrimary
                        )
                    }
                }
            }
        }
    }
}
