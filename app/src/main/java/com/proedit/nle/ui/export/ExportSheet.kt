package com.proedit.nle.ui.export

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.proedit.nle.domain.model.BitrateModeOption
import com.proedit.nle.domain.model.DynamicRangeOption
import com.proedit.nle.domain.model.ExportConfig
import com.proedit.nle.domain.model.VideoCodecOption
import com.proedit.nle.domain.model.VideoResolution
import com.proedit.nle.media.engine.ExportStatus
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

@Composable
fun ExportSheet(
    config: ExportConfig,
    exportStatus: ExportStatus,
    savedToGallery: Boolean,
    onUpdateConfig: (ExportConfig) -> Unit,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onSaveToGallery: (String) -> Unit,
    onShareExport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(10.dp))
                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .padding(16.dp)
                .testTag("export_sheet_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Export Video",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Close",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(4.dp)
                )
            }

            // 1. Resolution Selection
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Resolution", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                val resolutions = listOf(
                    VideoResolution.HD_720P,
                    VideoResolution.FHD_1080P,
                    VideoResolution.UHD_4K
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    resolutions.forEach { res ->
                        val isSelected = config.resolution.width == res.width && config.resolution.height == res.height
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (isSelected) AccentPrimary.copy(alpha = 0.2f) else SurfaceElevated,
                                    RoundedCornerShape(5.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) AccentPrimary else BorderSubtle,
                                    RoundedCornerShape(5.dp)
                                )
                                .clickable { onUpdateConfig(config.copy(resolution = res)) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = res.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) AccentPrimary else TextPrimary
                            )
                        }
                    }
                }
            }

            // 2. Frame Rate Selection
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Frame Rate", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                val fpsOptions = listOf(24.0, 30.0, 60.0)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fpsOptions.forEach { fps ->
                        val isSelected = config.fps == fps
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (isSelected) AccentPrimary.copy(alpha = 0.2f) else SurfaceElevated,
                                    RoundedCornerShape(5.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) AccentPrimary else BorderSubtle,
                                    RoundedCornerShape(5.dp)
                                )
                                .clickable { onUpdateConfig(config.copy(fps = fps)) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${fps.toInt()} fps",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) AccentPrimary else TextPrimary
                            )
                        }
                    }
                }
            }

            // 3. Video Codec & Bitrate Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VideoCodecOption.entries.forEach { codec ->
                    val isSelected = config.videoCodec == codec
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (isSelected) AccentSecondary.copy(alpha = 0.2f) else SurfaceElevated,
                                RoundedCornerShape(5.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) AccentSecondary else BorderSubtle,
                                RoundedCornerShape(5.dp)
                            )
                            .clickable { onUpdateConfig(config.copy(videoCodec = codec)) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = codec.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) AccentSecondary else TextPrimary
                        )
                    }
                }

                BitrateModeOption.entries.forEach { mode ->
                    val isSelected = config.bitrateMode == mode
                    Box(
                        modifier = Modifier
                            .weight(0.6f)
                            .background(
                                if (isSelected) SurfaceElevated else BackgroundPrimary,
                                RoundedCornerShape(5.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) TextPrimary else BorderSubtle,
                                RoundedCornerShape(5.dp)
                            )
                            .clickable { onUpdateConfig(config.copy(bitrateMode = mode)) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) TextPrimary else TextSecondary
                        )
                    }
                }
            }

            // 4. Target Bitrate & Color Space
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Bitrate", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(
                        text = "${config.bitrateMbps} Mbps",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentPrimary
                    )
                }
                Slider(
                    value = config.bitrateMbps.toFloat(),
                    onValueChange = { onUpdateConfig(config.copy(bitrateMbps = it.toInt())) },
                    valueRange = 8f..80f,
                    colors = SliderDefaults.colors(
                        thumbColor = AccentPrimary,
                        activeTrackColor = AccentPrimary,
                        inactiveTrackColor = BackgroundPrimary
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DynamicRangeOption.entries.forEach { hdrOpt ->
                        val isSelected = config.dynamicRange == hdrOpt
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (isSelected) SurfaceElevated else BackgroundPrimary,
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    0.5.dp,
                                    if (isSelected) AccentPrimary else BorderSubtle,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { onUpdateConfig(config.copy(dynamicRange = hdrOpt)) }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = hdrOpt.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) AccentPrimary else TextSecondary
                            )
                        }
                    }
                }
            }

            // 5. Real-time Progress / Completed Actions
            when (exportStatus) {
                is ExportStatus.InProgress -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { exportStatus.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = AccentPrimary,
                            trackColor = BackgroundPrimary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%d / %d frames (%.0f%%)",
                                    exportStatus.renderedFrames,
                                    exportStatus.totalFrames,
                                    exportStatus.progress * 100f
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentPrimary
                            )
                            Text(
                                text = "Cancel",
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticRed,
                                modifier = Modifier.clickable { onCancelExport() }
                            )
                        }
                    }
                }
                is ExportStatus.Completed -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SemanticGreen.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                            .border(1.dp, SemanticGreen, RoundedCornerShape(6.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val sizeMb = exportStatus.fileSizeBytes / (1024f * 1024f)
                        Text(
                            text = String.format(
                                Locale.US,
                                "Exported in %.1fs (%.2f MB)",
                                exportStatus.elapsedMs / 1000f,
                                sizeMb
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = SemanticGreen
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(SurfaceElevated, RoundedCornerShape(4.dp))
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable(enabled = !savedToGallery) {
                                        onSaveToGallery(exportStatus.outputPath)
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (savedToGallery) "Saved to Movies" else "Save to Movies",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (savedToGallery) SemanticGreen else TextPrimary
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(AccentSecondary.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .border(1.dp, AccentSecondary, RoundedCornerShape(4.dp))
                                    .clickable { onShareExport(exportStatus.outputPath) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Share MP4",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentSecondary
                                )
                            }
                        }
                    }
                }
                is ExportStatus.Failed -> {
                    Text(
                        text = exportStatus.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticRed
                    )
                }
                else -> {}
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AccentPrimary, RoundedCornerShape(6.dp))
                    .clickable { onStartExport() }
                    .padding(vertical = 10.dp)
                    .testTag("start_export_confirm_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Export MP4",
                    style = MaterialTheme.typography.titleSmall,
                    color = BackgroundPrimary
                )
            }
        }
    }
}
