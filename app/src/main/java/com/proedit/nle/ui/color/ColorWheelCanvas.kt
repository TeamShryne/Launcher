package com.proedit.nle.ui.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.proedit.nle.domain.model.ColorWheelValue
import com.proedit.nle.ui.theme.AccentPrimary
import com.proedit.nle.ui.theme.BackgroundPrimary
import com.proedit.nle.ui.theme.BorderSubtle
import com.proedit.nle.ui.theme.SurfaceElevated
import com.proedit.nle.ui.theme.TextPrimary
import com.proedit.nle.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

@Composable
fun ColorWheelCanvas(
    title: String,
    value: ColorWheelValue,
    unityValue: Float,
    minMaster: Float,
    maxMaster: Float,
    onValueChange: (ColorWheelValue) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .background(SurfaceElevated, RoundedCornerShape(6.dp))
            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .padding(6.dp)
            .testTag("color_wheel_${title.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Circular Hue/Saturation Puck Wheel
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .pointerInput(value) {
                        detectTapGestures(
                            onDoubleTap = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onReset()
                            }
                        )
                    }
                    .pointerInput(value, unityValue) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val radius = (min(size.width, size.height) / 2f).coerceAtLeast(1f)
                            val dx = (dragAmount.x / radius) * 0.45f
                            val dy = -(dragAmount.y / radius) * 0.45f

                            // Convert 2D puck movement into RGB differential offsets
                            val nextR = (value.r + dx * 0.8f + dy * 0.3f).coerceIn(unityValue - 0.8f, unityValue + 0.8f)
                            val nextG = (value.g - dx * 0.4f + dy * 0.6f).coerceIn(unityValue - 0.8f, unityValue + 0.8f)
                            val nextB = (value.b - dx * 0.4f - dy * 0.8f).coerceIn(unityValue - 0.8f, unityValue + 0.8f)
                            onValueChange(value.copy(r = nextR, g = nextG, b = nextB))
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = min(cx, cy) * 0.92f

                    val sweepColors = listOf(
                        Color(0xFFEF4444),
                        Color(0xFFF59E0B),
                        Color(0xFF10B981),
                        Color(0xFF06B6D4),
                        Color(0xFF3B82F6),
                        Color(0xFFA855F7),
                        Color(0xFFEF4444)
                    )
                    drawCircle(
                        brush = Brush.sweepGradient(sweepColors, Offset(cx, cy)),
                        radius = radius,
                        center = Offset(cx, cy),
                        alpha = 0.32f
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(BackgroundPrimary, Color.Transparent),
                            center = Offset(cx, cy),
                            radius = radius
                        ),
                        radius = radius,
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = BorderSubtle,
                        radius = radius,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.5f)
                    )

                    // Center Crosshair
                    drawLine(BorderSubtle, Offset(cx - radius, cy), Offset(cx + radius, cy), 1f)
                    drawLine(BorderSubtle, Offset(cx, cy - radius), Offset(cx, cy + radius), 1f)

                    // Compute Puck offset from RGB differential relative to unityValue
                    val dr = value.r - unityValue
                    val dg = value.g - unityValue
                    val db = value.b - unityValue
                    val px = (cx + (dr - 0.5f * dg - 0.5f * db) * radius * 0.85f)
                        .coerceIn(cx - radius * 0.85f, cx + radius * 0.85f)
                    val py = (cy - (0.5f * dg - 0.5f * db) * radius * 0.85f)
                        .coerceIn(cy - radius * 0.85f, cy + radius * 0.85f)

                    drawCircle(color = AccentPrimary, radius = 5.5f, center = Offset(px, py))
                    drawCircle(color = BackgroundPrimary, radius = 2.2f, center = Offset(px, py))
                }
            }

            // Vertical Master Luminance Thumb Wheel
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(74.dp)
                    .background(BackgroundPrimary, RoundedCornerShape(3.dp))
                    .border(1.dp, BorderSubtle, RoundedCornerShape(3.dp))
                    .pointerInput(value, minMaster, maxMaster) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val span = (maxMaster - minMaster).coerceAtLeast(0.1f)
                            val delta = -(dragAmount.y / size.height.toFloat().coerceAtLeast(1f)) * span
                            val nextMaster = (value.master + delta).coerceIn(minMaster, maxMaster)
                            onValueChange(value.copy(master = nextMaster))
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val norm = ((value.master - minMaster) / (maxMaster - minMaster).coerceAtLeast(0.01f))
                        .coerceIn(0f, 1f)
                    val indicatorY = size.height * (1f - norm)

                    for (tick in 0..8) {
                        val ty = (tick / 8f) * size.height
                        drawLine(
                            color = BorderSubtle,
                            start = Offset(2f, ty),
                            end = Offset(size.width - 2f, ty),
                            strokeWidth = 1f
                        )
                    }
                    drawLine(
                        color = AccentPrimary,
                        start = Offset(0f, indicatorY),
                        end = Offset(size.width, indicatorY),
                        strokeWidth = 2.5f
                    )
                }
            }
        }

        // Technical Monospace Readout
        Text(
            text = String.format(
                Locale.US,
                "R%.2f G%.2f B%.2f Y%.2f",
                value.r,
                value.g,
                value.b,
                value.master
            ),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
            color = TextSecondary
        )
    }
}
