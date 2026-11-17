package com.proedit.nle.ui.editor

import android.net.Uri
import com.proedit.nle.domain.model.AudioEffects
import com.proedit.nle.domain.model.BezierControlPoints
import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.ColorGrade
import com.proedit.nle.domain.model.EditMode
import com.proedit.nle.domain.model.ExportConfig
import com.proedit.nle.domain.model.TimelineState
import com.proedit.nle.domain.model.TransformProperty
import com.proedit.nle.media.engine.ExportStatus
import com.proedit.nle.ui.scopes.ScopeAnalysisResult
import com.proedit.nle.ui.scopes.VideoScopeMode

enum class InspectorTab(val label: String) {
    TIMELINE_ONLY("TIMELINE"),
    COLOR_GRADING("COLOR"),
    SPEED_CURVES("CURVES"),
    AUDIO_EQ("AUDIO EQ")
}

object EditorContract {

    data class UiState(
        val timeline: TimelineState = TimelineState(),
        val isProjectLoading: Boolean = false,
        val projectLoaded: Boolean = false,
        val isImportingMedia: Boolean = false,
        val importError: String? = null,
        val isPlaying: Boolean = false,
        val isProxyModeEnabled: Boolean = false,
        val isScopesVisible: Boolean = true,
        val activeScopeMode: VideoScopeMode = VideoScopeMode.RGB_PARADE,
        val scopeAnalysis: ScopeAnalysisResult = ScopeAnalysisResult(),
        val activeInspectorTab: InspectorTab = InspectorTab.TIMELINE_ONLY,
        val activeTransformProperty: TransformProperty = TransformProperty.SPEED,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val isExportSheetOpen: Boolean = false,
        val exportConfig: ExportConfig = ExportConfig(),
        val exportStatus: ExportStatus = ExportStatus.Idle,
        val droppedFramesCount: Int = 0
    ) {
        val selectedClip: Clip?
            get() = timeline.findClip(timeline.selectedClipId)
                ?: timeline.activeVideoClipAt(timeline.playheadPositionUs)
                ?: timeline.tracks.flatMap { it.clips }.firstOrNull()
    }

    sealed interface Intent {
        data class LoadProject(val projectId: String) : Intent
        data object SaveProjectNow : Intent
        data class ImportVideoUris(val uris: List<Uri>) : Intent
        data class ImportAudioUri(val uri: Uri) : Intent
        data object ClearImportError : Intent
        data class SeekTo(val positionUs: Long) : Intent
        data class StepFrames(val frameDelta: Int) : Intent
        data object TogglePlayPause : Intent
        data object ToggleProxyMode : Intent
        data object ToggleScopesOverlay : Intent
        data class SelectScopeMode(val mode: VideoScopeMode) : Intent
        data object ToggleMagneticSnap : Intent
        data class SelectEditMode(val editMode: EditMode) : Intent
        data class SelectInspectorTab(val tab: InspectorTab) : Intent
        data class SelectClip(val clipId: String?, val trackId: String? = null) : Intent
        data object SplitAtPlayhead : Intent
        data class SplitClipAt(val clipId: String, val timestampUs: Long) : Intent
        data class RippleTrimClip(val clipId: String, val trimStart: Boolean, val deltaUs: Long) : Intent
        data class RollEditClips(val leftClipId: String, val rightClipId: String, val deltaUs: Long) : Intent
        data class SlipEditClip(val clipId: String, val sourceDeltaUs: Long) : Intent
        data class SlideEditClip(val clipId: String, val timelineDeltaUs: Long) : Intent
        data class PerformSmartTrim(val clipId: String, val trimStart: Boolean, val deltaUs: Long) : Intent
        data class MoveClip(val clipId: String, val newStartUs: Long, val targetTrackId: String? = null) : Intent
        data class DeleteSelectedClip(val isRipple: Boolean = true) : Intent
        data class UpdateColorGrade(val clipId: String, val colorGrade: ColorGrade) : Intent
        data class UpdateAudioEffects(val clipId: String, val audioEffects: AudioEffects) : Intent
        data class UpdateJLCutOffset(val clipId: String, val audioOffsetUs: Long) : Intent
        data class SelectTransformProperty(val property: TransformProperty) : Intent
        data class AddOrToggleKeyframeAtPlayhead(val property: TransformProperty, val value: Float) : Intent
        data class UpdateBezierCurve(val controlPoints: BezierControlPoints) : Intent
        data class ApplySpeedRampPreset(
            val startSpeed: Float,
            val endSpeed: Float,
            val easing: BezierControlPoints
        ) : Intent
        data class ToggleTrackMute(val trackId: String) : Intent
        data class ToggleTrackLock(val trackId: String) : Intent
        data class SetTimelineZoom(val zoomPixelsPerSecond: Float) : Intent
        data object Undo : Intent
        data object Redo : Intent
        data class SetExportSheetOpen(val open: Boolean) : Intent
        data class UpdateExportConfig(val config: ExportConfig) : Intent
        data object StartExport : Intent
        data object CancelExport : Intent
    }
}
