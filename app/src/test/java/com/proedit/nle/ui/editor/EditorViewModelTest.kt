package com.proedit.nle.ui.editor

import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.ColorGrade
import com.proedit.nle.domain.model.TimelineState
import com.proedit.nle.domain.model.Track
import com.proedit.nle.domain.model.TrackType
import com.proedit.nle.domain.model.VideoResolution
import com.proedit.nle.ui.editor.EditorContract.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildTestTimeline(): TimelineState {
        val clipA = Clip(
            id = "clip_v1_a",
            uri = "file:///data/user/0/com.proedit.nle/files/projects/proj_1/media/take1.mp4",
            name = "take1.mp4",
            timelineStartUs = 0L,
            durationUs = 4_000_000L,
            sourceStartUs = 0L,
            sourceMaxDurationUs = 10_000_000L
        )
        val clipB = Clip(
            id = "clip_v1_b",
            uri = "file:///data/user/0/com.proedit.nle/files/projects/proj_1/media/take2.mp4",
            name = "take2.mp4",
            timelineStartUs = 4_000_000L,
            durationUs = 3_000_000L,
            sourceStartUs = 0L,
            sourceMaxDurationUs = 10_000_000L
        )
        return TimelineState(
            id = "proj_1",
            name = "Test Edit",
            fps = 30.0,
            resolution = VideoResolution.FHD_1080P,
            tracks = listOf(
                Track(id = "track_v1", name = "V1", type = TrackType.VIDEO, clips = listOf(clipA, clipB)),
                Track(id = "track_a1", name = "A1", type = TrackType.AUDIO)
            ),
            selectedClipId = clipA.id,
            selectedTrackId = "track_v1"
        )
    }

    @Test
    fun `dispatching SeekTo, SplitAtPlayhead, RippleTrimClip, UpdateColorGrade, Undo, and Redo transitions uiState deterministically`() = runTest(testDispatcher) {
        val viewModel = EditorViewModel(initialTimelineState = buildTestTimeline())
        val initialClipCount = viewModel.uiState.value.timeline.totalClipCount
        assertEquals(2, initialClipCount)
        assertEquals(7_000_000L, viewModel.uiState.value.timeline.totalDurationUs)
        assertFalse(viewModel.uiState.value.canUndo)

        // 1. SeekTo 2.0s (inside clip_v1_a which spans 0..4s)
        viewModel.dispatch(Intent.SelectClip("clip_v1_a", "track_v1"))
        viewModel.dispatch(Intent.SeekTo(2_000_000L))
        assertEquals(2_000_000L, viewModel.uiState.value.timeline.playheadPositionUs)

        // 2. SplitAtPlayhead
        viewModel.dispatch(Intent.SplitAtPlayhead)
        assertEquals(3, viewModel.uiState.value.timeline.totalClipCount)
        assertTrue(viewModel.uiState.value.canUndo)
        val leftHalf = viewModel.uiState.value.timeline.findClip("clip_v1_a")!!
        assertEquals(2_000_000L, leftHalf.durationUs)

        // 3. RippleTrimClip on clip_v1_a by -500,000 us
        viewModel.dispatch(Intent.RippleTrimClip("clip_v1_a", trimStart = false, deltaUs = -500_000L))
        val trimmedLeft = viewModel.uiState.value.timeline.findClip("clip_v1_a")!!
        assertEquals(1_500_000L, trimmedLeft.durationUs)

        // 4. UpdateColorGrade on clip_v1_a
        val customGrade = ColorGrade(temperature = 35f, contrast = 1.25f, saturation = 1.15f)
        viewModel.dispatch(Intent.UpdateColorGrade("clip_v1_a", customGrade))
        val gradedClip = viewModel.uiState.value.timeline.findClip("clip_v1_a")!!
        assertEquals(35f, gradedClip.colorGrade.temperature, 1e-4f)
        assertEquals(1.25f, gradedClip.colorGrade.contrast, 1e-4f)

        // 5. Undo ColorGrade -> Undo RippleTrim -> Undo Split
        viewModel.dispatch(Intent.Undo)
        assertEquals(0f, viewModel.uiState.value.timeline.findClip("clip_v1_a")!!.colorGrade.temperature, 1e-4f)
        assertTrue(viewModel.uiState.value.canRedo)

        viewModel.dispatch(Intent.Undo)
        assertEquals(2_000_000L, viewModel.uiState.value.timeline.findClip("clip_v1_a")!!.durationUs)

        viewModel.dispatch(Intent.Undo)
        assertEquals(4_000_000L, viewModel.uiState.value.timeline.findClip("clip_v1_a")!!.durationUs)
        assertEquals(initialClipCount, viewModel.uiState.value.timeline.totalClipCount)
        assertFalse(viewModel.uiState.value.canUndo)

        // 6. Redo all 3 commands
        viewModel.dispatch(Intent.Redo)
        viewModel.dispatch(Intent.Redo)
        viewModel.dispatch(Intent.Redo)
        assertEquals(35f, viewModel.uiState.value.timeline.findClip("clip_v1_a")!!.colorGrade.temperature, 1e-4f)
        assertEquals(1_500_000L, viewModel.uiState.value.timeline.findClip("clip_v1_a")!!.durationUs)
        assertFalse(viewModel.uiState.value.canRedo)
    }

    @Test
    fun `empty project timeline has zero totalDurationUs and clamps seek safely`() = runTest(testDispatcher) {
        val emptyTimeline = TimelineState.createEmptyProjectTimeline(
            id = "proj_empty",
            name = "Blank",
            resolution = VideoResolution.VERTICAL_1080P,
            fps = 24.0
        )
        val viewModel = EditorViewModel(initialTimelineState = emptyTimeline)
        assertEquals(0L, viewModel.uiState.value.timeline.totalDurationUs)
        assertEquals(10_000_000L, viewModel.uiState.value.timeline.scrubBoundsUs)

        viewModel.dispatch(Intent.SeekTo(5_000_000L))
        assertEquals(0L, viewModel.uiState.value.timeline.playheadPositionUs)
    }
}
