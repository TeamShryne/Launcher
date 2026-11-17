package com.proedit.nle.ui.color

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.proedit.nle.domain.lut.CubeLutParser
import com.proedit.nle.domain.model.ColorGrade
import com.proedit.nle.domain.model.ColorWheelValue
import com.proedit.nle.domain.model.LutSpec
import com.proedit.nle.ui.theme.AccentPrimary
import com.proedit.nle.ui.theme.AccentSecondary
import com.proedit.nle.ui.theme.BackgroundPrimary
import com.proedit.nle.ui.theme.BorderSubtle
import com.proedit.nle.ui.theme.SurfaceDark
import com.proedit.nle.ui.theme.SurfaceElevated
import com.proedit.nle.ui.theme.TextPrimary
import com.proedit.nle.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.abs

@Composable
fun ColorGradingPanel(
    colorGrade: ColorGrade,
    onUpdateColorGrade: (ColorGrade) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val presets = remember { CubeLutParser.builtInPresets() }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("color_grading_panel"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Lift (Shadows)
        ColorWheelCanvas(
            title = "LIFT (SHADOWS)",
            value = colorGrade.lift,
            unityValue = 0f,
            minMaster = -0.5f,
            maxMaster = 0.5f,
            onValueChange = { onUpdateColorGrade(colorGrade.copy(lift = it)) },
            onReset = { onUpdateColorGrade(colorGrade.copy(lift = ColorWheelValue(0f, 0f, 0f, 0f))) },
            modifier = Modifier
                .width(126.dp)
                .fillMaxHeight()
        )

        // 2. Gamma (Midtones)
        ColorWheelCanvas(
            title = "GAMMA (MIDS)",
            value = colorGrade.gamma,
            unityValue = 1f,
            minMaster = 0.4f,
            maxMaster = 2.2f,
            onValueChange = { onUpdateColorGrade(colorGrade.copy(gamma = it)) },
            onReset = { onUpdateColorGrade(colorGrade.copy(gamma = ColorWheelValue(1f, 1f, 1f, 1f))) },
            modifier = Modifier
                .width(126.dp)
                .fillMaxHeight()
        )

        // 3. Gain (Highlights)
        ColorWheelCanvas(
            title = "GAIN (HIGHLIGHTS)",
            value = colorGrade.gain,
            unityValue = 1f,
            minMaster = 0.2f,
            maxMaster = 2.5f,
            onValueChange = { onUpdateColorGrade(colorGrade.copy(gain = it)) },
            onReset = { onUpdateColorGrade(colorGrade.copy(gain = ColorWheelValue(1f, 1f, 1f, 1f))) },
            modifier = Modifier
                .width(126.dp)
                .fillMaxHeight()
        )

        // 4. Interactive Monotone Cubic Hermite RGB Curves
        RgbCurvesCanvas(
            curves = colorGrade.rgbCurves,
            onCurvesChange = { onUpdateColorGrade(colorGrade.copy(rgbCurves = it)) },
            modifier = Modifier
                .width(142.dp)
                .fillMaxHeight()
        )

        // 5. Primaries & .cube 3D LUT Selector
        Column(
            modifier = Modifier
                .width(214.dp)
                .fillMaxHeight()
                .background(SurfaceElevated, RoundedCornerShape(6.dp))
                .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                .padding(6.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PRIMARIES & 3D LUT",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentPrimary
                )
                Text(
                    text = if (colorGrade.isBypassed) "BYPASSED" else "RESET ALL",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                    color = AccentSecondary,
                    modifier = Modifier
                        .clickable { onUpdateColorGrade(ColorGrade()) }
                        .testTag("reset_color_grade_button")
                )
            }

            CompactGradeSlider(
                label = "TEMP",
                value = colorGrade.temperature,
                range = -100f..100f,
                unity = 0f,
                format = "%+.0f",
                onValueChange = {
                    if (abs(it) < 1.5f) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onUpdateColorGrade(colorGrade.copy(temperature = it))
                }
            )
            CompactGradeSlider(
                label = "CONTRAST",
                value = colorGrade.contrast,
                range = 0.2f..2.0f,
                unity = 1.0f,
                format = "%.2f",
                onValueChange = {
                    if (abs(it - 1.0f) < 0.02f) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onUpdateColorGrade(colorGrade.copy(contrast = it))
                }
            )
            CompactGradeSlider(
                label = "SAT",
                value = colorGrade.saturation,
                range = 0.0f..2.0f,
                unity = 1.0f,
                format = "%.2f",
                onValueChange = {
                    if (abs(it - 1.0f) < 0.02f) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onUpdateColorGrade(colorGrade.copy(saturation = it))
                }
            )

            // 3D .cube LUT Preset Pills + Intensity
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                presets.entries.forEach { (lutName, cubeText) ->
                    val isSelected = colorGrade.lut?.name == lutName
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (isSelected) AccentPrimary.copy(alpha = 0.22f) else BackgroundPrimary,
                                RoundedCornerShape(3.dp)
                            )
                            .border(
                                0.5.dp,
                                if (isSelected) AccentPrimary else BorderSubtle,
                                RoundedCornerShape(3.dp)
                            )
                            .clickable {
                                val nextLut = if (isSelected) null else LutSpec(lutName, cubeText, 0.85f)
                                onUpdateColorGrade(colorGrade.copy(lut = nextLut))
                            }
                            .padding(horizontal = 3.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lutName.substringBefore(" "),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                            color = if (isSelected) AccentPrimary else TextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactGradeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unity: Float,
    format: String,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
            color = TextSecondary,
            modifier = Modifier.width(48.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 0.dp),
            colors = SliderDefaults.colors(
                thumbColor = AccentPrimary,
                activeTrackColor = AccentPrimary,
                inactiveTrackColor = BackgroundPrimary
            )
        )
        Text(
            text = String.format(Locale.US, format, value),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
            color = if (abs(value - unity) < 0.01f) TextSecondary else TextPrimary,
            modifier = Modifier.width(34.dp)
        )
    }
}
