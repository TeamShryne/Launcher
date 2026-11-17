package com.proedit.nle.ui.scopes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.proedit.nle.ui.theme.AccentPrimary
import com.proedit.nle.ui.theme.BackgroundPrimary
import com.proedit.nle.ui.theme.BorderSubtle
import com.proedit.nle.ui.theme.ScopeBlue
import com.proedit.nle.ui.theme.ScopeGreen
import com.proedit.nle.ui.theme.ScopeLuma
import com.proedit.nle.ui.theme.ScopeRed
import com.proedit.nle.ui.theme.SemanticRed
import com.proedit.nle.ui.theme.SurfaceDark
import com.proedit.nle.ui.theme.TextPrimary
import com.proedit.nle.ui.theme.TextSecondary
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun VideoScopesOverlay(
    analysis: ScopeAnalysisResult,
    activeMode: VideoScopeMode,
    onSelectMode: (VideoScopeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(216.dp)
            .height(148.dp)
            .background(BackgroundPrimary.copy(alpha = 0.90f), RoundedCornerShape(6.dp))
            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .padding(6.dp)
            .testTag("video_scopes_overlay")
    ) {
        // Scope Header Bar + Mode Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                VideoScopeMode.entries.forEach { mode ->
                    val isSelected = mode == activeMode
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) AccentPrimary.copy(alpha = 0.22f) else SurfaceDark,
                                RoundedCornerShape(3.dp)
                            )
                            .border(
                                0.5.dp,
                                if (isSelected) AccentPrimary else BorderSubtle,
                                RoundedCornerShape(3.dp)
                            )
                            .clickable { onSelectMode(mode) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .testTag("scope_mode_${mode.name.lowercase()}")
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                            color = if (isSelected) AccentPrimary else TextSecondary
                        )
                    }
                }
            }

            Text(
                text = "${analysis.peakIre} IRE",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = if (analysis.clippedPixelCount > 12) SemanticRed else TextSecondary
            )
        }

        // Scope Canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                when (activeMode) {
                    VideoScopeMode.LUMA_WAVEFORM -> drawLumaWaveform(analysis)
                    VideoScopeMode.RGB_PARADE -> drawRgbParade(analysis)
                    VideoScopeMode.HISTOGRAM -> drawHistogram(analysis)
                    VideoScopeMode.VECTORSCOPE -> drawVectorscope(analysis)
                }
            }
        }
    }
}

private fun DrawScope.drawIreGraticule() {
    val gridColor = Color(0xFF27272A)
    val levels = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f)
    for (lvl in levels) {
        val y = size.height * (1f - lvl)
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f
        )
    }
}

private fun DrawScope.drawLumaWaveform(analysis: ScopeAnalysisResult) {
    drawIreGraticule()
    val cols = analysis.columns
    val buckets = analysis.ireBuckets
    val colW = size.width / cols.toFloat()
    val rowH = size.height / buckets.toFloat()

    for (x in 0 until cols) {
        val base = x * buckets
        val px = x * colW
        for (b in 0 until buckets) {
            val count = analysis.lumaWaveform[base + b]
            if (count > 0) {
                val alpha = (count / 6f).coerceIn(0.18f, 0.95f)
                val py = size.height - (b + 1) * rowH
                drawRect(
                    color = ScopeLuma.copy(alpha = alpha),
                    topLeft = Offset(px, py),
                    size = Size(colW.coerceAtLeast(1.5f), rowH.coerceAtLeast(1.5f))
                )
            }
        }
    }
}

private fun DrawScope.drawRgbParade(analysis: ScopeAnalysisResult) {
    drawIreGraticule()
    val thirdW = size.width / 3f
    val cols = 64
    val buckets = analysis.ireBuckets
    val colW = (thirdW - 4f) / cols.toFloat()
    val rowH = size.height / buckets.toFloat()

    fun drawChannel(paradeData: IntArray, xOffset: Float, color: Color) {
        for (x in 0 until cols) {
            val base = x * buckets
            val px = xOffset + x * colW
            for (b in 0 until buckets) {
                val count = paradeData[base + b]
                if (count > 0) {
                    val alpha = (count / 8f).coerceIn(0.20f, 0.95f)
                    val py = size.height - (b + 1) * rowH
                    drawRect(
                        color = color.copy(alpha = alpha),
                        topLeft = Offset(px, py),
                        size = Size(colW.coerceAtLeast(1.2f), rowH.coerceAtLeast(1.5f))
                    )
                }
            }
        }
    }

    drawChannel(analysis.paradeR, 0f, ScopeRed)
    drawChannel(analysis.paradeG, thirdW + 2f, ScopeGreen)
    drawChannel(analysis.paradeB, thirdW * 2f + 4f, ScopeBlue)
}

private fun DrawScope.drawHistogram(analysis: ScopeAnalysisResult) {
    drawIreGraticule()
    val maxBin = maxOf(
        analysis.histogramR.maxOrNull() ?: 1,
        analysis.histogramG.maxOrNull() ?: 1,
        analysis.histogramB.maxOrNull() ?: 1,
        1
    ).toFloat()

    fun drawTrace(bins: IntArray, color: Color) {
        val path = Path()
        for (i in 0 until 256) {
            val x = (i / 255f) * size.width
            val norm = (bins[i] / maxBin).coerceIn(0f, 1f)
            val y = size.height * (1f - norm)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 1.6f))
    }

    drawTrace(analysis.histogramR, ScopeRed.copy(alpha = 0.85f))
    drawTrace(analysis.histogramG, ScopeGreen.copy(alpha = 0.85f))
    drawTrace(analysis.histogramB, ScopeBlue.copy(alpha = 0.85f))
    drawTrace(analysis.histogramLuma, TextPrimary.copy(alpha = 0.70f))
}

private fun DrawScope.drawVectorscope(analysis: ScopeAnalysisResult) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = min(cx, cy) * 0.92f
    val graticuleColor = Color(0xFF3F3F46)

    // Outer & 75% rings
    drawCircle(color = graticuleColor, radius = radius, center = Offset(cx, cy), style = Stroke(1f))
    drawCircle(color = graticuleColor.copy(alpha = 0.5f), radius = radius * 0.5f, center = Offset(cx, cy), style = Stroke(1f))

    // Crosshair
    drawLine(graticuleColor, Offset(cx - radius, cy), Offset(cx + radius, cy), 1f)
    drawLine(graticuleColor, Offset(cx, cy - radius), Offset(cx, cy + radius), 1f)

    // Skin-tone I-line (approx 123 degrees in standard vectorscope orientation)
    val iLineRad = Math.toRadians(-123.0)
    drawLine(
        color = AccentPrimary.copy(alpha = 0.65f),
        start = Offset(cx, cy),
        end = Offset(
            cx + (radius * cos(iLineRad)).toFloat(),
            cy + (radius * sin(iLineRad)).toFloat()
        ),
        strokeWidth = 1.5f
    )

    // Standard 75% Chrominance Target Boxes (R, Mg, B, Cy, G, Yl)
    val targets = listOf(
        Triple(-0.169f, 0.500f, ScopeRed),           // Red
        Triple(0.331f, 0.419f, Color(0xFFE879F9)),   // Magenta
        Triple(0.500f, -0.081f, ScopeBlue),          // Blue
        Triple(0.169f, -0.500f, Color(0xFF22D3EE)),  // Cyan
        Triple(-0.331f, -0.419f, ScopeGreen),        // Green
        Triple(-0.500f, 0.081f, AccentPrimary)       // Yellow
    )
    for ((cb, cr, targetColor) in targets) {
        val tx = cx + (cb * 1.5f) * radius
        val ty = cy - (cr * 1.5f) * radius
        drawRect(
            color = targetColor.copy(alpha = 0.75f),
            topLeft = Offset(tx - 3.5f, ty - 3.5f),
            size = Size(7f, 7f),
            style = Stroke(width = 1.3f)
        )
    }

    // Plot Cb/Cr density cloud
    val gridSize = 64
    for (gy in 0 until gridSize) {
        for (gx in 0 until gridSize) {
            val count = analysis.vectorscopeGrid[gy * gridSize + gx]
            if (count > 0) {
                val nx = (gx / (gridSize - 1f)) * 2f - 1f
                val ny = (gy / (gridSize - 1f)) * 2f - 1f
                val px = cx + nx * radius
                val py = cy + ny * radius
                val alpha = (count / 6f).coerceIn(0.25f, 0.95f)
                drawCircle(
                    color = ScopeGreen.copy(alpha = alpha),
                    radius = 1.8f,
                    center = Offset(px, py)
                )
            }
        }
    }
}
