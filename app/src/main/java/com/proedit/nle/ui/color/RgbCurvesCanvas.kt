package com.proedit.nle.ui.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.proedit.nle.domain.model.CurveChannel
import com.proedit.nle.domain.model.CurvePoint
import com.proedit.nle.domain.model.RgbCurves
import com.proedit.nle.ui.theme.BackgroundPrimary
import com.proedit.nle.ui.theme.BorderSubtle
import com.proedit.nle.ui.theme.ScopeBlue
import com.proedit.nle.ui.theme.ScopeGreen
import com.proedit.nle.ui.theme.ScopeRed
import com.proedit.nle.ui.theme.SurfaceElevated
import com.proedit.nle.ui.theme.TextPrimary
import com.proedit.nle.ui.theme.TextSecondary
import kotlin.math.hypot

@Composable
fun RgbCurvesCanvas(
    curves: RgbCurves,
    onCurvesChange: (RgbCurves) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeChannel by remember { mutableStateOf(CurveChannel.MASTER) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }

    val activePoints = when (activeChannel) {
        CurveChannel.MASTER -> curves.master
        CurveChannel.RED -> curves.red
        CurveChannel.GREEN -> curves.green
        CurveChannel.BLUE -> curves.blue
    }

    fun updateChannelPoints(updated: List<CurvePoint>) {
        val sorted = updated.sortedBy { it.x }
        val nextCurves = when (activeChannel) {
            CurveChannel.MASTER -> curves.copy(master = sorted)
            CurveChannel.RED -> curves.copy(red = sorted)
            CurveChannel.GREEN -> curves.copy(green = sorted)
            CurveChannel.BLUE -> curves.copy(blue = sorted)
        }
        onCurvesChange(nextCurves)
    }

    Column(
        modifier = modifier
            .background(SurfaceElevated, RoundedCornerShape(6.dp))
            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .padding(6.dp)
            .testTag("rgb_curves_editor"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CurveChannel.entries.forEach { channel ->
                    val isSelected = channel == activeChannel
                    val channelColor = when (channel) {
                        CurveChannel.MASTER -> TextPrimary
                        CurveChannel.RED -> ScopeRed
                        CurveChannel.GREEN -> ScopeGreen
                        CurveChannel.BLUE -> ScopeBlue
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) channelColor.copy(alpha = 0.22f) else BackgroundPrimary,
                                RoundedCornerShape(3.dp)
                            )
                            .border(
                                0.5.dp,
                                if (isSelected) channelColor else BorderSubtle,
                                RoundedCornerShape(3.dp)
                            )
                            .clickable { activeChannel = channel }
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = channel.name.first().toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = if (isSelected) channelColor else TextSecondary
                        )
                    }
                }
            }

            Text(
                text = "RESET",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                color = TextSecondary,
                modifier = Modifier.clickable {
                    onCurvesChange(RgbCurves())
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BackgroundPrimary, RoundedCornerShape(4.dp))
                .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                .padding(6.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(activeChannel, activePoints) {
                        detectTapGestures(
                            onDoubleTap = {
                                updateChannelPoints(RgbCurves.defaultCurve())
                            }
                        )
                    }
                    .pointerInput(activeChannel, activePoints) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                var bestIdx = 0
                                var bestDist = Float.MAX_VALUE
                                activePoints.forEachIndexed { index, pt ->
                                    val d = hypot(pos.x - pt.x * w, pos.y - (1f - pt.y) * h)
                                    if (d < bestDist) {
                                        bestDist = d
                                        bestIdx = index
                                    }
                                }
                                draggedIndex = bestIdx
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val idx = draggedIndex ?: return@detectDragGestures
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val nx = when (idx) {
                                    0 -> 0f
                                    activePoints.lastIndex -> 1f
                                    else -> (change.position.x / w).coerceIn(0.05f, 0.95f)
                                }
                                val ny = (1f - (change.position.y / h)).coerceIn(0f, 1f)
                                val nextList = activePoints.toMutableList()
                                if (idx in nextList.indices) {
                                    nextList[idx] = CurvePoint(nx, ny)
                                    updateChannelPoints(nextList)
                                }
                            },
                            onDragEnd = {
                                draggedIndex = null
                            }
                        )
                    }
            ) {
                val w = size.width
                val h = size.height

                // Grid
                for (i in 1..3) {
                    val f = i / 4f
                    drawLine(BorderSubtle, Offset(w * f, 0f), Offset(w * f, h), 1f)
                    drawLine(BorderSubtle, Offset(0f, h * f), Offset(w, h * f), 1f)
                }

                // Draw all 4 evaluated Monotone Cubic Hermite curves
                fun drawCurveLut(pts: List<CurvePoint>, color: Color, strokeWidth: Float) {
                    val lut = RgbCurves.evaluateMonotoneHermite256(pts)
                    val path = Path()
                    for (i in 0 until 256) {
                        val x = (i / 255f) * w
                        val y = (1f - lut[i]) * h
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = color, style = Stroke(width = strokeWidth))
                }

                drawCurveLut(curves.red, ScopeRed.copy(alpha = if (activeChannel == CurveChannel.RED) 1f else 0.35f), 1.8f)
                drawCurveLut(curves.green, ScopeGreen.copy(alpha = if (activeChannel == CurveChannel.GREEN) 1f else 0.35f), 1.8f)
                drawCurveLut(curves.blue, ScopeBlue.copy(alpha = if (activeChannel == CurveChannel.BLUE) 1f else 0.35f), 1.8f)
                drawCurveLut(curves.master, TextPrimary.copy(alpha = if (activeChannel == CurveChannel.MASTER) 1f else 0.45f), 2.2f)

                // Control points of active channel
                val activeColor = when (activeChannel) {
                    CurveChannel.MASTER -> TextPrimary
                    CurveChannel.RED -> ScopeRed
                    CurveChannel.GREEN -> ScopeGreen
                    CurveChannel.BLUE -> ScopeBlue
                }
                for (pt in activePoints) {
                    val px = pt.x * w
                    val py = (1f - pt.y) * h
                    drawCircle(color = activeColor, radius = 4.5f, center = Offset(px, py))
                    drawCircle(color = BackgroundPrimary, radius = 1.8f, center = Offset(px, py))
                }
            }
        }
    }
}
