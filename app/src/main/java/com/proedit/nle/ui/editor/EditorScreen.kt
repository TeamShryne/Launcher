package com.proedit.nle.ui.editor

import android.app.Application
import android.content.Intent as AndroidIntent
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.proedit.nle.domain.math.SpeedRampIntegrator
import com.proedit.nle.domain.math.TimecodeCalculator
import com.proedit.nle.domain.model.EditMode
import com.proedit.nle.domain.model.TrackType
import com.proedit.nle.media.engine.ExportPipeline
import com.proedit.nle.media.engine.ExportStatus
import com.proedit.nle.media.engine.NleMediaEngine
import com.proedit.nle.media.extractor.AudioWaveformExtractor
import com.proedit.nle.media.extractor.FrameThumbnailExtractor
import com.proedit.nle.ui.audio.AudioEqPanel
import com.proedit.nle.ui.color.ColorGradingPanel
import com.proedit.nle.ui.curves.BezierGraphEditor
import com.proedit.nle.ui.editor.EditorContract.Intent
import com.proedit.nle.ui.export.ExportSheet
import com.proedit.nle.ui.scopes.ScopeAnalysisResult
import com.proedit.nle.ui.scopes.ScopeAnalyzer
import com.proedit.nle.ui.scopes.VideoScopesOverlay
import com.proedit.nle.ui.theme.AccentPrimary
import com.proedit.nle.ui.theme.AccentSecondary
import com.proedit.nle.ui.theme.BackgroundPrimary
import com.proedit.nle.ui.theme.BorderSubtle
import com.proedit.nle.ui.theme.SemanticRed
import com.proedit.nle.ui.theme.SurfaceDark
import com.proedit.nle.ui.theme.SurfaceElevated
import com.proedit.nle.ui.theme.TextPrimary
import com.proedit.nle.ui.theme.TextSecondary
import com.proedit.nle.ui.timeline.TimelineCanvas
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun EditorScreen(
    projectId: String,
    onBackToProjects: () -> Unit,
    viewModel: EditorViewModel = viewModel(
        key = projectId,
        factory = EditorViewModel.factory(LocalContext.current.applicationContext as Application)
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val mediaEngine = remember(context) { NleMediaEngine(context) }
    val thumbnailExtractor = remember(context) { FrameThumbnailExtractor(context) }
    val waveformExtractor = remember(context) { AudioWaveformExtractor(context) }
    val exportPipeline = remember(context) { ExportPipeline(context) }

    var exportJob by remember { mutableStateOf<Job?>(null) }
    var currentExportStatus by remember { mutableStateOf<ExportStatus>(ExportStatus.Idle) }
    var savedToGallery by remember { mutableStateOf(false) }
    var mediaRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(projectId) {
        viewModel.dispatch(Intent.LoadProject(projectId))
    }

    DisposableEffect(mediaEngine) {
        onDispose { mediaEngine.release() }
    }

    // Back navigation closes sheets/inspectors first, then saves project & returns to Projects
    BackHandler {
        when {
            uiState.isExportSheetOpen -> {
                viewModel.dispatch(Intent.SetExportSheetOpen(false))
            }
            uiState.activeInspectorTab != InspectorTab.TIMELINE_ONLY -> {
                viewModel.dispatch(Intent.SelectInspectorTab(InspectorTab.TIMELINE_ONLY))
            }
            else -> {
                viewModel.dispatch(Intent.SaveProjectNow)
                onBackToProjects()
            }
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.dispatch(Intent.ImportVideoUris(uris))
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.dispatch(Intent.ImportAudioUri(uri))
        }
    }

    val activeClip = uiState.timeline.activeVideoClipAt(uiState.timeline.playheadPositionUs)

    // Pre-extract real video thumbnails and MediaCodec PCM waveforms for timeline clips
    LaunchedEffect(uiState.timeline.tracks) {
        val allTracks = uiState.timeline.tracks
        for (track in allTracks) {
            for (clip in track.clips) {
                if (track.type == TrackType.VIDEO) {
                    launch {
                        val extracted = thumbnailExtractor.extractThumbnailStrip(clip, bucketCount = 8)
                        if (extracted > 0) mediaRevision++
                    }
                }
                launch {
                    waveformExtractor.extractWaveformBuckets(clip, bucketCount = 96)
                    mediaRevision++
                }
            }
        }
    }

    // Synchronize ExoPlayer hardware playback & OpenGL shaders with TimelineState
    LaunchedEffect(
        uiState.timeline.playheadPositionUs,
        activeClip?.id,
        activeClip?.colorGrade,
        activeClip?.sourceStartUs,
        activeClip?.durationUs,
        uiState.isPlaying
    ) {
        mediaEngine.syncWithTimeline(uiState.timeline, uiState.isPlaying)

        if (activeClip == null) {
            if (uiState.isScopesVisible) {
                viewModel.updateScopeAnalysis(ScopeAnalysisResult())
            }
            return@LaunchedEffect
        }

        if (!uiState.isPlaying && uiState.isScopesVisible) {
            val clipOffsetUs = (uiState.timeline.playheadPositionUs - activeClip.timelineStartUs)
                .coerceAtLeast(0L)
            val sourceTimeUs = SpeedRampIntegrator.mapTimelineToSourceUs(
                timelineOffsetUs = clipOffsetUs,
                sourceStartUs = activeClip.sourceStartUs,
                speedCurve = activeClip.speedCurve
            )
            val bmp = mediaEngine.captureLiveFrameBitmap(128, 72)
                ?: thumbnailExtractor.extractSingleFrame(activeClip.uri, sourceTimeUs, 128, 72)
            if (bmp != null) {
                viewModel.updateScopeAnalysis(ScopeAnalyzer.analyzeFrame(bmp))
            }
        }
    }

    // Sample live TextureView frames periodically during playback for real-time scopes
    LaunchedEffect(uiState.isPlaying, uiState.isScopesVisible) {
        while (uiState.isPlaying && uiState.isScopesVisible) {
            delay(120L)
            val liveBmp = mediaEngine.captureLiveFrameBitmap(128, 72)
            if (liveBmp != null) {
                viewModel.updateScopeAnalysis(ScopeAnalyzer.analyzeFrame(liveBmp))
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = BackgroundPrimary
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(BackgroundPrimary)
        ) {
            // =========================================================================
            // TOP 42%: MONITOR & SCOPES + HEADER BAR
            // =========================================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.42f)
                    .background(BackgroundPrimary)
            ) {
                TopMonitorBar(
                    projectName = uiState.timeline.name,
                    smpteTimecode = TimecodeCalculator.formatSmpte(
                        timeUs = uiState.timeline.playheadPositionUs,
                        fps = uiState.timeline.fps,
                        dropFrame = uiState.timeline.isDropFrame
                    ),
                    fps = uiState.timeline.fps,
                    canUndo = uiState.canUndo,
                    canRedo = uiState.canRedo,
                    isProxyEnabled = uiState.isProxyModeEnabled,
                    isScopesVisible = uiState.isScopesVisible,
                    onBackToProjects = {
                        viewModel.dispatch(Intent.SaveProjectNow)
                        onBackToProjects()
                    },
                    onUndo = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.dispatch(Intent.Undo)
                    },
                    onRedo = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.dispatch(Intent.Redo)
                    },
                    onToggleProxy = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.dispatch(Intent.ToggleProxyMode)
                    },
                    onToggleScopes = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.dispatch(Intent.ToggleScopesOverlay)
                    },
                    onOpenExport = {
                        viewModel.dispatch(Intent.SetExportSheetOpen(true))
                    }
                )

                // Aspect-Ratio-Locked Monitor Viewport + Real-Time Scopes Overlay
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundPrimary)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val targetAspect = uiState.timeline.resolution.aspectRatio
                    val containerAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else 16f / 9f
                    val viewportModifier = if (containerAspect >= targetAspect) {
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(targetAspect)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(targetAspect)
                    }

                    Box(
                        modifier = viewportModifier
                            .background(SurfaceDark, RoundedCornerShape(4.dp))
                            .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                            .testTag("video_monitor_viewport"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (activeClip != null) {
                            AndroidView(
                                factory = { ctx ->
                                    TextureView(ctx).also { tv ->
                                        mediaEngine.attachVideoTextureView(tv)
                                    }
                                },
                                update = { tv ->
                                    mediaEngine.attachVideoTextureView(tv)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            DisposableEffect(Unit) {
                                mediaEngine.attachVideoTextureView(null)
                                onDispose { }
                            }
                            if (uiState.isProjectLoading || uiState.isImportingMedia) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = AccentPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = if (uiState.isImportingMedia) "Importing media..." else "Loading project...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            } else if (uiState.timeline.totalClipCount == 0) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VideoLibrary,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Text(
                                        text = "No clips in timeline",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextSecondary
                                    )
                                    Box(
                                        modifier = Modifier
                                            .background(AccentPrimary, RoundedCornerShape(4.dp))
                                            .clickable {
                                                videoPickerLauncher.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                                )
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                            .testTag("empty_monitor_import_button")
                                    ) {
                                        Text(
                                            text = "Import Video",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = BackgroundPrimary
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "No video at playhead",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        // Compact Monitor HUD (Clip Name & Resolution/Proxy)
                        if (activeClip != null) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .background(BackgroundPrimary.copy(alpha = 0.72f), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = activeClip.name,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (uiState.isProxyModeEnabled) "PROXY" else uiState.timeline.resolution.label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = if (uiState.isProxyModeEnabled) AccentSecondary else AccentPrimary
                                )
                            }
                        }
                    }

                    // Transport Controls Overlay (Bottom-Left)
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .background(SurfaceDark.copy(alpha = 0.92f), RoundedCornerShape(6.dp))
                            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastRewind,
                            contentDescription = stringResource(R.string.cd_step_backward),
                            tint = TextPrimary,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.dispatch(Intent.StepFrames(-1))
                                }
                                .testTag("step_backward_button")
                        )
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.cd_play_pause),
                            tint = AccentPrimary,
                            modifier = Modifier
                                .size(24.dp)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.dispatch(Intent.TogglePlayPause)
                                }
                                .testTag("play_pause_button")
                        )
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = stringResource(R.string.cd_step_forward),
                            tint = TextPrimary,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.dispatch(Intent.StepFrames(1))
                                }
                                .testTag("step_forward_button")
                        )
                    }

                    // Import Error Notice (Bottom-Center)
                    uiState.importError?.let { err ->
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .background(SemanticRed.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                                .clickable { viewModel.dispatch(Intent.ClearImportError) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary
                            )
                        }
                    }

                    // Real-Time Video Scopes Overlay (Top-Right)
                    if (uiState.isScopesVisible) {
                        VideoScopesOverlay(
                            analysis = uiState.scopeAnalysis,
                            activeMode = uiState.activeScopeMode,
                            onSelectMode = { viewModel.dispatch(Intent.SelectScopeMode(it)) },
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                    }
                }
            }

            // =========================================================================
            // MIDDLE 8%: CONTEXTUAL TOOL DOCK
            // =========================================================================
            ContextualToolDock(
                activeEditMode = uiState.timeline.activeEditMode,
                activeInspectorTab = uiState.activeInspectorTab,
                isMagneticSnapEnabled = uiState.timeline.isMagneticSnapEnabled,
                onSplitAtPlayhead = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.dispatch(Intent.SplitAtPlayhead)
                },
                onCycleEditMode = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val modes = EditMode.entries
                    val nextIdx = (modes.indexOf(uiState.timeline.activeEditMode) + 1) % modes.size
                    viewModel.dispatch(Intent.SelectEditMode(modes[nextIdx]))
                },
                onQuickJLCut = {
                    val clip = uiState.selectedClip ?: return@ContextualToolDock
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val nextOffset = when {
                        clip.audioOffsetUs == 0L -> -250_000L
                        clip.audioOffsetUs < 0L -> 250_000L
                        else -> 0L
                    }
                    viewModel.dispatch(Intent.UpdateJLCutOffset(clip.id, nextOffset))
                },
                onSelectInspectorTab = { tab ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.dispatch(Intent.SelectInspectorTab(tab))
                },
                onAddKeyframe = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.dispatch(
                        Intent.AddOrToggleKeyframeAtPlayhead(uiState.activeTransformProperty, 1.25f)
                    )
                },
                onToggleMagneticSnap = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.dispatch(Intent.ToggleMagneticSnap)
                },
                onImportVideo = {
                    videoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                onImportAudio = {
                    audioPickerLauncher.launch(arrayOf("audio/*"))
                },
                onRippleDelete = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.dispatch(Intent.DeleteSelectedClip(isRipple = true))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.08f)
            )

            // =========================================================================
            // BOTTOM 50%: MULTI-TRACK TIMELINE & CONTEXTUAL INSPECTOR DRAWER
            // =========================================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.50f)
                    .background(BackgroundPrimary)
            ) {
                val selectedClip = uiState.selectedClip
                if (uiState.activeInspectorTab != InspectorTab.TIMELINE_ONLY && selectedClip != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.46f)
                            .border(0.5.dp, BorderSubtle)
                    ) {
                        when (uiState.activeInspectorTab) {
                            InspectorTab.COLOR_GRADING -> {
                                ColorGradingPanel(
                                    colorGrade = selectedClip.colorGrade,
                                    onUpdateColorGrade = { newGrade ->
                                        viewModel.dispatch(Intent.UpdateColorGrade(selectedClip.id, newGrade))
                                    }
                                )
                            }
                            InspectorTab.SPEED_CURVES -> {
                                BezierGraphEditor(
                                    selectedClip = selectedClip,
                                    activeProperty = uiState.activeTransformProperty,
                                    onSelectProperty = { viewModel.dispatch(Intent.SelectTransformProperty(it)) },
                                    onUpdateBezierPoints = { viewModel.dispatch(Intent.UpdateBezierCurve(it)) },
                                    onApplySpeedPreset = { s0, s1, easing ->
                                        viewModel.dispatch(Intent.ApplySpeedRampPreset(s0, s1, easing))
                                    }
                                )
                            }
                            InspectorTab.AUDIO_EQ -> {
                                AudioEqPanel(
                                    audioEffects = selectedClip.audioEffects,
                                    audioOffsetUs = selectedClip.audioOffsetUs,
                                    onUpdateAudioEffects = { newEq ->
                                        viewModel.dispatch(Intent.UpdateAudioEffects(selectedClip.id, newEq))
                                    },
                                    onUpdateAudioOffsetUs = { newOffset ->
                                        viewModel.dispatch(Intent.UpdateJLCutOffset(selectedClip.id, newOffset))
                                    }
                                )
                            }
                            InspectorTab.TIMELINE_ONLY -> {}
                        }
                    }
                }

                // Multi-Track Timeline Canvas
                val currentRev = mediaRevision
                TimelineCanvas(
                    state = uiState.timeline,
                    waveformLookup = { clip ->
                        @Suppress("UNUSED_EXPRESSION")
                        currentRev
                        waveformExtractor.getWaveformCached(clip) ?: FloatArray(0)
                    },
                    thumbnailLookup = { clip, bucketIdx ->
                        @Suppress("UNUSED_EXPRESSION")
                        currentRev
                        thumbnailExtractor.getCachedThumbnail(clip.id, bucketIdx)
                    },
                    onSeekTo = { viewModel.dispatch(Intent.SeekTo(it)) },
                    onSelectClip = { clipId, trackId -> viewModel.dispatch(Intent.SelectClip(clipId, trackId)) },
                    onToggleTrackMute = { viewModel.dispatch(Intent.ToggleTrackMute(it)) },
                    onToggleTrackLock = { viewModel.dispatch(Intent.ToggleTrackLock(it)) },
                    onZoomChange = { viewModel.dispatch(Intent.SetTimelineZoom(it)) },
                    onSplitClipAt = { clipId, timeUs -> viewModel.dispatch(Intent.SplitClipAt(clipId, timeUs)) },
                    onTrimClip = { clipId, trimStart, deltaUs ->
                        viewModel.dispatch(Intent.PerformSmartTrim(clipId, trimStart, deltaUs))
                    },
                    onMoveClip = { clipId, newStartUs ->
                        viewModel.dispatch(Intent.MoveClip(clipId, newStartUs))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(
                            if (uiState.activeInspectorTab != InspectorTab.TIMELINE_ONLY && selectedClip != null) {
                                0.54f
                            } else {
                                1f
                            }
                        )
                )
            }
        }

        // Export Sheet Modal
        if (uiState.isExportSheetOpen) {
            ExportSheet(
                config = uiState.exportConfig,
                exportStatus = currentExportStatus,
                savedToGallery = savedToGallery,
                onUpdateConfig = { viewModel.dispatch(Intent.UpdateExportConfig(it)) },
                onStartExport = {
                    savedToGallery = false
                    exportJob?.cancel()
                    exportJob = coroutineScope.launch {
                        exportPipeline.exportTimeline(uiState.timeline, uiState.exportConfig)
                            .collect { status ->
                                currentExportStatus = status
                            }
                    }
                },
                onCancelExport = {
                    exportPipeline.cancelExport()
                    exportJob?.cancel()
                    currentExportStatus = ExportStatus.Cancelled
                },
                onSaveToGallery = { path ->
                    coroutineScope.launch {
                        savedToGallery = exportPipeline.saveExportToGallery(path)
                    }
                },
                onShareExport = { path ->
                    val file = File(path)
                    if (file.exists()) {
                        val contentUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val shareIntent = AndroidIntent(AndroidIntent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(AndroidIntent.EXTRA_STREAM, contentUri)
                            addFlags(AndroidIntent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(AndroidIntent.createChooser(shareIntent, "Share video"))
                    }
                },
                onDismiss = {
                    viewModel.dispatch(Intent.SetExportSheetOpen(false))
                }
            )
        }
    }
}

@Composable
private fun TopMonitorBar(
    projectName: String,
    smpteTimecode: String,
    fps: Double,
    canUndo: Boolean,
    canRedo: Boolean,
    isProxyEnabled: Boolean,
    isScopesVisible: Boolean,
    onBackToProjects: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleProxy: () -> Unit,
    onToggleScopes: () -> Unit,
    onOpenExport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(SurfaceDark)
            .border(0.5.dp, BorderSubtle)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Back to Projects + Project Name + SMPTE Timecode + FPS
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(SurfaceElevated, RoundedCornerShape(4.dp))
                    .border(0.5.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .clickable { onBackToProjects() }
                    .testTag("back_to_projects_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back_to_projects),
                    tint = TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(
                modifier = Modifier
                    .background(BackgroundPrimary, RoundedCornerShape(4.dp))
                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
                    .testTag("smpte_timecode_readout")
            ) {
                Text(
                    text = smpteTimecode,
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentPrimary
                )
            }

            Column {
                Text(
                    text = projectName,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (fps % 1.0 == 0.0) "${fps.toInt()} fps" else "$fps fps",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                    color = TextSecondary
                )
            }
        }

        // Right: Undo/Redo, Proxy, Scopes, Export
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(SurfaceElevated, RoundedCornerShape(4.dp))
                    .border(0.5.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .clickable(enabled = canUndo) { onUndo() }
                    .testTag("undo_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.cd_undo),
                    tint = if (canUndo) TextPrimary else TextSecondary.copy(alpha = 0.35f),
                    modifier = Modifier.size(15.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(SurfaceElevated, RoundedCornerShape(4.dp))
                    .border(0.5.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .clickable(enabled = canRedo) { onRedo() }
                    .testTag("redo_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = stringResource(R.string.cd_redo),
                    tint = if (canRedo) TextPrimary else TextSecondary.copy(alpha = 0.35f),
                    modifier = Modifier.size(15.dp)
                )
            }

            Box(
                modifier = Modifier
                    .background(
                        if (isProxyEnabled) AccentSecondary.copy(alpha = 0.2f) else SurfaceElevated,
                        RoundedCornerShape(4.dp)
                    )
                    .border(
                        0.5.dp,
                        if (isProxyEnabled) AccentSecondary else BorderSubtle,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { onToggleProxy() }
                    .padding(horizontal = 6.dp, vertical = 5.dp)
                    .testTag("proxy_toggle_button")
            ) {
                Text(
                    text = "PROXY",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = if (isProxyEnabled) AccentSecondary else TextSecondary
                )
            }

            Box(
                modifier = Modifier
                    .background(
                        if (isScopesVisible) AccentPrimary.copy(alpha = 0.2f) else SurfaceElevated,
                        RoundedCornerShape(4.dp)
                    )
                    .border(
                        0.5.dp,
                        if (isScopesVisible) AccentPrimary else BorderSubtle,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { onToggleScopes() }
                    .padding(horizontal = 6.dp, vertical = 5.dp)
                    .testTag("scopes_toggle_button")
            ) {
                Text(
                    text = "SCOPES",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = if (isScopesVisible) AccentPrimary else TextSecondary
                )
            }

            Box(
                modifier = Modifier
                    .background(AccentPrimary, RoundedCornerShape(4.dp))
                    .clickable { onOpenExport() }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .testTag("export_trigger_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = stringResource(R.string.cd_export_video),
                        tint = BackgroundPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "EXPORT",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                        color = BackgroundPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextualToolDock(
    activeEditMode: EditMode,
    activeInspectorTab: InspectorTab,
    isMagneticSnapEnabled: Boolean,
    onSplitAtPlayhead: () -> Unit,
    onCycleEditMode: () -> Unit,
    onQuickJLCut: () -> Unit,
    onSelectInspectorTab: (InspectorTab) -> Unit,
    onAddKeyframe: () -> Unit,
    onToggleMagneticSnap: () -> Unit,
    onImportVideo: () -> Unit,
    onImportAudio: () -> Unit,
    onRippleDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(SurfaceDark)
            .border(0.5.dp, BorderSubtle)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
            .testTag("contextual_tool_dock"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolDockButton(
            icon = Icons.Default.Add,
            label = "VIDEO",
            isActive = false,
            activeColor = AccentPrimary,
            onClick = onImportVideo,
            testTag = "tool_import_video"
        )

        ToolDockButton(
            icon = Icons.Default.AudioFile,
            label = "AUDIO",
            isActive = false,
            activeColor = AccentSecondary,
            onClick = onImportAudio,
            testTag = "tool_import_audio"
        )

        ToolDockButton(
            icon = Icons.Default.ContentCut,
            label = "SPLIT",
            isActive = activeEditMode == EditMode.BLADE,
            onClick = onSplitAtPlayhead,
            testTag = "tool_blade_split"
        )

        ToolDockButton(
            icon = Icons.Default.SwapHoriz,
            label = activeEditMode.shortCode,
            isActive = true,
            onClick = onCycleEditMode,
            testTag = "tool_trim_mode_cycle"
        )

        ToolDockButton(
            icon = Icons.Default.GridOn,
            label = "J/L CUT",
            isActive = false,
            onClick = onQuickJLCut,
            testTag = "tool_jl_split"
        )

        ToolDockButton(
            icon = Icons.Default.ShowChart,
            label = "CURVES",
            isActive = activeInspectorTab == InspectorTab.SPEED_CURVES,
            onClick = { onSelectInspectorTab(InspectorTab.SPEED_CURVES) },
            testTag = "tool_speed_curves"
        )

        ToolDockButton(
            icon = Icons.Default.Palette,
            label = "COLOR",
            isActive = activeInspectorTab == InspectorTab.COLOR_GRADING,
            onClick = { onSelectInspectorTab(InspectorTab.COLOR_GRADING) },
            testTag = "tool_color_grading"
        )

        ToolDockButton(
            icon = Icons.Default.Equalizer,
            label = "EQ",
            isActive = activeInspectorTab == InspectorTab.AUDIO_EQ,
            onClick = { onSelectInspectorTab(InspectorTab.AUDIO_EQ) },
            testTag = "tool_audio_eq"
        )

        ToolDockButton(
            icon = Icons.Default.Key,
            label = "+KEY",
            isActive = false,
            onClick = onAddKeyframe,
            testTag = "tool_add_keyframe"
        )

        ToolDockButton(
            icon = Icons.Default.GridOn,
            label = if (isMagneticSnapEnabled) "SNAP" else "FREE",
            isActive = isMagneticSnapEnabled,
            activeColor = AccentSecondary,
            onClick = onToggleMagneticSnap,
            testTag = "tool_magnetic_snap"
        )

        Spacer(modifier = Modifier.width(4.dp))

        ToolDockButton(
            icon = Icons.Default.DeleteOutline,
            label = "DELETE",
            isActive = false,
            activeColor = SemanticRed,
            onClick = onRippleDelete,
            testTag = "tool_ripple_delete"
        )
    }
}

@Composable
private fun ToolDockButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color = AccentPrimary,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .height(30.dp)
            .background(
                if (isActive) activeColor.copy(alpha = 0.20f) else SurfaceElevated,
                RoundedCornerShape(4.dp)
            )
            .border(
                1.dp,
                if (isActive) activeColor else BorderSubtle,
                RoundedCornerShape(4.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 7.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) activeColor else TextPrimary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
            color = if (isActive) activeColor else TextPrimary
        )
    }
}
