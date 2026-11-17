package com.proedit.nle.ui.editor

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.proedit.nle.data.local.ProjectRepository
import com.proedit.nle.domain.command.AddClipCommand
import com.proedit.nle.domain.command.BladeSplitClipCommand
import com.proedit.nle.domain.command.CommandManager
import com.proedit.nle.domain.command.DeleteClipCommand
import com.proedit.nle.domain.command.JLCutAudioOffsetCommand
import com.proedit.nle.domain.command.MoveClipCommand
import com.proedit.nle.domain.command.RippleTrimCommand
import com.proedit.nle.domain.command.RollEditCommand
import com.proedit.nle.domain.command.SlideEditCommand
import com.proedit.nle.domain.command.SlipEditCommand
import com.proedit.nle.domain.command.UpdateAudioEffectsCommand
import com.proedit.nle.domain.command.UpdateColorGradeCommand
import com.proedit.nle.domain.command.UpsertKeyframeCommand
import com.proedit.nle.domain.math.TimecodeCalculator
import com.proedit.nle.domain.model.BezierControlPoints
import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.EditMode
import com.proedit.nle.domain.model.ExportConfig
import com.proedit.nle.domain.model.Keyframe
import com.proedit.nle.domain.model.TimelineState
import com.proedit.nle.domain.model.TrackType
import com.proedit.nle.domain.model.TransformProperty
import com.proedit.nle.media.extractor.MediaImportManager
import com.proedit.nle.ui.editor.EditorContract.Intent
import com.proedit.nle.ui.editor.EditorContract.UiState
import com.proedit.nle.ui.scopes.ScopeAnalysisResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class EditorViewModel(
    private val repository: ProjectRepository? = null,
    private val appContext: Context? = null,
    initialTimelineState: TimelineState = TimelineState()
) : ViewModel() {

    private val commandManager = CommandManager(initialState = initialTimelineState)

    private val _uiState = MutableStateFlow(
        UiState(
            timeline = commandManager.state.value,
            canUndo = commandManager.canUndo,
            canRedo = commandManager.canRedo
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var playbackJob: Job? = null
    private var autoSaveJob: Job? = null
    private var loadedProjectId: String? = null

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.LoadProject -> handleLoadProject(intent.projectId)
            is Intent.SaveProjectNow -> handleSaveProjectNow()
            is Intent.ImportVideoUris -> handleImportVideoUris(intent.uris)
            is Intent.ImportAudioUri -> handleImportAudioUri(intent.uri)
            is Intent.ClearImportError -> _uiState.update { it.copy(importError = null) }
            is Intent.SeekTo -> handleSeekTo(intent.positionUs, persist = true)
            is Intent.StepFrames -> handleStepFrames(intent.frameDelta)
            is Intent.TogglePlayPause -> handleTogglePlayPause()
            is Intent.ToggleProxyMode -> {
                _uiState.update { it.copy(isProxyModeEnabled = !it.isProxyModeEnabled) }
            }
            is Intent.ToggleScopesOverlay -> {
                _uiState.update { it.copy(isScopesVisible = !it.isScopesVisible) }
            }
            is Intent.SelectScopeMode -> {
                _uiState.update { it.copy(activeScopeMode = intent.mode) }
            }
            is Intent.ToggleMagneticSnap -> {
                val updated = commandManager.updateTransient {
                    it.copy(isMagneticSnapEnabled = !it.isMagneticSnapEnabled)
                }
                syncTimelineState(updated, persist = true)
            }
            is Intent.SelectEditMode -> {
                val updated = commandManager.updateTransient {
                    it.copy(activeEditMode = intent.editMode)
                }
                syncTimelineState(updated, persist = true)
            }
            is Intent.SelectInspectorTab -> {
                val nextTab = if (_uiState.value.activeInspectorTab == intent.tab) {
                    InspectorTab.TIMELINE_ONLY
                } else {
                    intent.tab
                }
                _uiState.update { it.copy(activeInspectorTab = nextTab) }
            }
            is Intent.SelectClip -> {
                val updated = commandManager.updateTransient {
                    it.copy(
                        selectedClipId = intent.clipId,
                        selectedTrackId = intent.trackId ?: it.findTrackForClip(intent.clipId)?.id
                    )
                }
                syncTimelineState(updated, persist = false)
            }
            is Intent.SplitAtPlayhead -> handleSplitAtPlayhead()
            is Intent.SplitClipAt -> {
                val next = commandManager.execute(
                    BladeSplitClipCommand(
                        clipId = intent.clipId,
                        splitTimestampUs = intent.timestampUs
                    )
                )
                syncTimelineState(next, persist = true)
            }
            is Intent.RippleTrimClip -> {
                val next = commandManager.execute(
                    RippleTrimCommand(
                        clipId = intent.clipId,
                        trimStart = intent.trimStart,
                        deltaUs = intent.deltaUs
                    )
                )
                syncTimelineState(next, persist = true)
            }
            is Intent.RollEditClips -> {
                val next = commandManager.execute(
                    RollEditCommand(
                        leftClipId = intent.leftClipId,
                        rightClipId = intent.rightClipId,
                        deltaUs = intent.deltaUs
                    )
                )
                syncTimelineState(next, persist = true)
            }
            is Intent.SlipEditClip -> {
                val next = commandManager.execute(
                    SlipEditCommand(
                        clipId = intent.clipId,
                        sourceDeltaUs = intent.sourceDeltaUs
                    )
                )
                syncTimelineState(next, persist = true)
            }
            is Intent.SlideEditClip -> {
                val next = commandManager.execute(
                    SlideEditCommand(
                        clipId = intent.clipId,
                        timelineDeltaUs = intent.timelineDeltaUs
                    )
                )
                syncTimelineState(next, persist = true)
            }
            is Intent.PerformSmartTrim -> handlePerformSmartTrim(intent.clipId, intent.trimStart, intent.deltaUs)
            is Intent.MoveClip -> {
                val next = commandManager.execute(
                    MoveClipCommand(
                        clipId = intent.clipId,
                        newTimelineStartUs = intent.newStartUs,
                        targetTrackId = intent.targetTrackId
                    )
                )
                syncTimelineState(next, persist = true)
            }
            is Intent.DeleteSelectedClip -> {
                val targetId = _uiState.value.selectedClip?.id ?: return
                val next = commandManager.execute(
                    DeleteClipCommand(
                        clipId = targetId,
                        isRippleDelete = intent.isRipple
                    )
                )
                syncTimelineState(next, persist = true)
            }
            is Intent.UpdateColorGrade -> {
                val next = commandManager.execute(
                    UpdateColorGradeCommand(
                        clipId = intent.clipId,
                        newColorGrade = intent.colorGrade
                    )
                )
                syncTimelineState(next, persist = true)
            }
            is Intent.UpdateAudioEffects -> {
                val next = commandManager.execute(
                    UpdateAudioEffectsCommand(
                        clipId = intent.clipId,
                        newAudioEffects = intent.audioEffects
                    )
                )
                syncTimelineState(next, persist = true)
            }
            is Intent.UpdateJLCutOffset -> {
                val next = commandManager.execute(
                    JLCutAudioOffsetCommand(
                        clipId = intent.clipId,
                        newAudioOffsetUs = intent.audioOffsetUs
                    )
                )
                syncTimelineState(next, persist = true)
            }
            is Intent.SelectTransformProperty -> {
                _uiState.update { it.copy(activeTransformProperty = intent.property) }
            }
            is Intent.AddOrToggleKeyframeAtPlayhead -> handleAddKeyframeAtPlayhead(intent.property, intent.value)
            is Intent.UpdateBezierCurve -> handleUpdateBezierCurve(intent.controlPoints)
            is Intent.ApplySpeedRampPreset -> handleApplySpeedRampPreset(
                intent.startSpeed,
                intent.endSpeed,
                intent.easing
            )
            is Intent.ToggleTrackMute -> {
                val updated = commandManager.updateTransient { state ->
                    state.copy(
                        tracks = state.tracks.map {
                            if (it.id == intent.trackId) it.copy(isMuted = !it.isMuted) else it
                        }
                    )
                }
                syncTimelineState(updated, persist = true)
            }
            is Intent.ToggleTrackLock -> {
                val updated = commandManager.updateTransient { state ->
                    state.copy(
                        tracks = state.tracks.map {
                            if (it.id == intent.trackId) it.copy(isLocked = !it.isLocked) else it
                        }
                    )
                }
                syncTimelineState(updated, persist = true)
            }
            is Intent.SetTimelineZoom -> {
                val updated = commandManager.updateTransient {
                    it.copy(zoomPixelsPerSecond = intent.zoomPixelsPerSecond.coerceIn(20f, 480f))
                }
                syncTimelineState(updated, persist = false)
            }
            is Intent.Undo -> {
                val restored = commandManager.undo()
                syncTimelineState(restored, persist = true)
            }
            is Intent.Redo -> {
                val reapplied = commandManager.redo()
                syncTimelineState(reapplied, persist = true)
            }
            is Intent.SetExportSheetOpen -> {
                _uiState.update { it.copy(isExportSheetOpen = intent.open) }
            }
            is Intent.UpdateExportConfig -> {
                _uiState.update { it.copy(exportConfig = intent.config) }
            }
            is Intent.StartExport -> {}
            is Intent.CancelExport -> {}
        }
    }

    fun updateScopeAnalysis(result: ScopeAnalysisResult) {
        _uiState.update { it.copy(scopeAnalysis = result) }
    }

    private fun handleLoadProject(projectId: String) {
        val repo = repository ?: return
        if (loadedProjectId == projectId && _uiState.value.projectLoaded) return
        loadedProjectId = projectId

        viewModelScope.launch {
            _uiState.update { it.copy(isProjectLoading = true) }
            val loaded = repo.loadProjectTimeline(projectId)
            if (loaded != null) {
                commandManager.reset(loaded)
                val safeFileName = loaded.name
                    .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
                    .trim('_')
                    .ifBlank { "Export" } + ".mp4"
                _uiState.update {
                    it.copy(
                        timeline = loaded,
                        isProjectLoading = false,
                        projectLoaded = true,
                        canUndo = commandManager.canUndo,
                        canRedo = commandManager.canRedo,
                        exportConfig = ExportConfig(
                            resolution = loaded.resolution,
                            fps = loaded.fps,
                            outputFileName = safeFileName
                        )
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isProjectLoading = false,
                        projectLoaded = true
                    )
                }
            }
        }
    }

    private fun handleSaveProjectNow() {
        playbackJob?.cancel()
        _uiState.update { it.copy(isPlaying = false) }
        val repo = repository ?: return
        if (!_uiState.value.projectLoaded) return
        autoSaveJob?.cancel()
        val snapshot = commandManager.state.value
        viewModelScope.launch {
            repo.saveProjectTimeline(snapshot)
        }
    }

    private fun handleImportVideoUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ctx = appContext ?: return
        val projectId = commandManager.state.value.id

        viewModelScope.launch {
            _uiState.update { it.copy(isImportingMedia = true, importError = null) }
            var currentState = commandManager.state.value
            val v1Track = currentState.tracks.firstOrNull { it.type == TrackType.VIDEO && it.id == "track_v1" }
                ?: currentState.tracks.firstOrNull { it.type == TrackType.VIDEO }
            val targetTrackId = v1Track?.id ?: "track_v1"

            var cursorUs = v1Track?.sortedClips?.lastOrNull()?.timelineEndUs ?: 0L
            var importedAny = false
            var firstImportedId: String? = null

            for (uri in uris) {
                val meta = MediaImportManager.importMediaToProject(ctx, projectId, uri)
                if (meta != null) {
                    val clipId = "clip_${UUID.randomUUID().toString().take(8)}"
                    if (firstImportedId == null) firstImportedId = clipId
                    val clip = Clip(
                        id = clipId,
                        uri = meta.localUriString,
                        name = meta.displayName,
                        timelineStartUs = cursorUs,
                        durationUs = meta.durationUs,
                        sourceStartUs = 0L,
                        sourceMaxDurationUs = meta.durationUs,
                        proceduralSeed = meta.displayName.hashCode()
                    )
                    currentState = commandManager.execute(
                        AddClipCommand(
                            trackId = targetTrackId,
                            clip = clip,
                            resolveCollisionsMagnetically = true
                        )
                    )
                    cursorUs += meta.durationUs
                    importedAny = true
                }
            }

            if (importedAny && firstImportedId != null) {
                currentState = commandManager.updateTransient {
                    it.copy(
                        selectedClipId = firstImportedId,
                        selectedTrackId = targetTrackId
                    )
                }
                syncTimelineState(currentState, persist = true)
            } else {
                _uiState.update {
                    it.copy(importError = "Could not read selected video file.")
                }
            }
            _uiState.update { it.copy(isImportingMedia = false) }
        }
    }

    private fun handleImportAudioUri(uri: Uri) {
        val ctx = appContext ?: return
        val projectId = commandManager.state.value.id

        viewModelScope.launch {
            _uiState.update { it.copy(isImportingMedia = true, importError = null) }
            val meta = MediaImportManager.importMediaToProject(ctx, projectId, uri)
            if (meta != null) {
                val state = commandManager.state.value
                val a1Track = state.tracks.firstOrNull { it.type == TrackType.AUDIO }
                val targetTrackId = a1Track?.id ?: "track_a1"
                val startUs = state.playheadPositionUs.coerceAtLeast(0L)
                val clipId = "clip_aud_${UUID.randomUUID().toString().take(8)}"
                val audioClip = Clip(
                    id = clipId,
                    uri = meta.localUriString,
                    name = meta.displayName,
                    timelineStartUs = startUs,
                    durationUs = meta.durationUs,
                    sourceStartUs = 0L,
                    sourceMaxDurationUs = meta.durationUs,
                    proceduralSeed = meta.displayName.hashCode()
                )
                val updated = commandManager.execute(
                    AddClipCommand(
                        trackId = targetTrackId,
                        clip = audioClip,
                        resolveCollisionsMagnetically = true
                    )
                )
                val selected = commandManager.updateTransient {
                    updated.copy(selectedClipId = clipId, selectedTrackId = targetTrackId)
                }
                syncTimelineState(selected, persist = true)
            } else {
                _uiState.update {
                    it.copy(importError = "Could not read selected audio file.")
                }
            }
            _uiState.update { it.copy(isImportingMedia = false) }
        }
    }

    private fun handleSeekTo(positionUs: Long, persist: Boolean) {
        val maxDurationUs = commandManager.state.value.totalDurationUs.coerceAtLeast(0L)
        val clamped = positionUs.coerceIn(0L, maxDurationUs)
        val updated = commandManager.updateTransient {
            it.copy(playheadPositionUs = clamped)
        }
        syncTimelineState(updated, persist = persist)
    }

    private fun handleStepFrames(frameDelta: Int) {
        val current = commandManager.state.value
        val frameUs = TimecodeCalculator.frameDurationUs(current.fps)
        val nextPos = (current.playheadPositionUs + frameDelta * frameUs)
            .coerceIn(0L, current.totalDurationUs.coerceAtLeast(0L))
        handleSeekTo(nextPos, persist = true)
    }

    private fun handleTogglePlayPause() {
        val totalUs = commandManager.state.value.totalDurationUs
        if (totalUs <= 0L) {
            _uiState.update { it.copy(isPlaying = false) }
            return
        }

        val nextPlaying = !_uiState.value.isPlaying
        if (nextPlaying && commandManager.state.value.playheadPositionUs >= totalUs) {
            handleSeekTo(0L, persist = false)
        }
        _uiState.update { it.copy(isPlaying = nextPlaying) }
        playbackJob?.cancel()
        if (nextPlaying) {
            playbackJob = viewModelScope.launch {
                var lastTickNs = System.nanoTime()
                while (_uiState.value.isPlaying) {
                    delay(33L)
                    val nowNs = System.nanoTime()
                    val deltaUs = ((nowNs - lastTickNs) / 1000L).coerceIn(1_000L, 100_000L)
                    lastTickNs = nowNs

                    val cur = commandManager.state.value
                    val nextUs = cur.playheadPositionUs + deltaUs
                    if (nextUs >= cur.totalDurationUs) {
                        handleSeekTo(0L, persist = false)
                        _uiState.update { it.copy(isPlaying = false) }
                        break
                    } else {
                        handleSeekTo(nextUs, persist = false)
                    }
                }
            }
        }
    }

    private fun handleSplitAtPlayhead() {
        val state = commandManager.state.value
        val playhead = state.playheadPositionUs
        val targetClip = state.findClip(state.selectedClipId)
            ?.takeIf { playhead > it.timelineStartUs && playhead < it.timelineEndUs }
            ?: state.activeVideoClipAt(playhead)
            ?: return

        val next = commandManager.execute(
            BladeSplitClipCommand(
                clipId = targetClip.id,
                splitTimestampUs = playhead
            )
        )
        syncTimelineState(next, persist = true)
    }

    private fun handlePerformSmartTrim(clipId: String, trimStart: Boolean, deltaUs: Long) {
        val state = commandManager.state.value
        val track = state.findTrackForClip(clipId) ?: return
        val sorted = track.sortedClips
        val idx = sorted.indexOfFirst { it.id == clipId }
        if (idx == -1) return

        val command = when (state.activeEditMode) {
            EditMode.RIPPLE, EditMode.BLADE -> RippleTrimCommand(clipId, trimStart, deltaUs)
            EditMode.ROLL -> {
                if (trimStart && idx > 0) {
                    RollEditCommand(sorted[idx - 1].id, clipId, deltaUs)
                } else if (!trimStart && idx < sorted.lastIndex) {
                    RollEditCommand(clipId, sorted[idx + 1].id, deltaUs)
                } else {
                    RippleTrimCommand(clipId, trimStart, deltaUs)
                }
            }
            EditMode.SLIP -> SlipEditCommand(clipId, deltaUs)
            EditMode.SLIDE -> SlideEditCommand(clipId, deltaUs)
        }
        val next = commandManager.execute(command)
        syncTimelineState(next, persist = true)
    }

    private fun handleAddKeyframeAtPlayhead(property: TransformProperty, value: Float) {
        val state = commandManager.state.value
        val clip = _uiState.value.selectedClip ?: return
        val offsetInClip = (state.playheadPositionUs - clip.timelineStartUs).coerceIn(0L, clip.durationUs)
        val kf = Keyframe(
            id = "kf_${property.name}_$offsetInClip",
            timeOffsetUs = offsetInClip,
            value = value
        )
        val next = commandManager.execute(UpsertKeyframeCommand(clip.id, property, kf))
        syncTimelineState(next, persist = true)
    }

    private fun handleUpdateBezierCurve(controlPoints: BezierControlPoints) {
        val clip = _uiState.value.selectedClip ?: return
        val kf0 = Keyframe(
            id = "kf_speed_start_${clip.id}",
            timeOffsetUs = 0L,
            value = clip.speedCurve.keyframes.firstOrNull()?.value ?: 1.0f,
            easing = controlPoints
        )
        val next = commandManager.execute(
            UpsertKeyframeCommand(clip.id, TransformProperty.SPEED, kf0)
        )
        syncTimelineState(next, persist = true)
    }

    private fun handleApplySpeedRampPreset(
        startSpeed: Float,
        endSpeed: Float,
        easing: BezierControlPoints
    ) {
        val clip = _uiState.value.selectedClip ?: return
        val kfStart = Keyframe(
            id = "kf_speed_0_${clip.id}",
            timeOffsetUs = 0L,
            value = startSpeed,
            easing = easing
        )
        val kfEnd = Keyframe(
            id = "kf_speed_1_${clip.id}",
            timeOffsetUs = clip.durationUs,
            value = endSpeed,
            easing = easing
        )
        commandManager.execute(UpsertKeyframeCommand(clip.id, TransformProperty.SPEED, kfStart))
        val next = commandManager.execute(UpsertKeyframeCommand(clip.id, TransformProperty.SPEED, kfEnd))
        syncTimelineState(next, persist = true)
    }

    private fun syncTimelineState(newTimeline: TimelineState, persist: Boolean) {
        _uiState.update {
            it.copy(
                timeline = newTimeline,
                canUndo = commandManager.canUndo,
                canRedo = commandManager.canRedo
            )
        }
        if (persist && repository != null && _uiState.value.projectLoaded) {
            scheduleAutoSave(newTimeline)
        }
    }

    private fun scheduleAutoSave(state: TimelineState) {
        val repo = repository ?: return
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(400L)
            repo.saveProjectTimeline(state)
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        autoSaveJob?.cancel()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return EditorViewModel(
                        repository = ProjectRepository(application),
                        appContext = application.applicationContext
                    ) as T
                }
            }
    }
}
