package com.proedit.nle.domain.command

import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.TimelineState
import com.proedit.nle.domain.model.Track
import com.proedit.nle.domain.model.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TimelineCommandEngineTest {

    private fun createInitialThreeClipState(): TimelineState {
        val clip1 = Clip(
            id = "c1",
            uri = "procedural://1",
            name = "Clip 1",
            timelineStartUs = 0L,
            durationUs = 3_000_000L,
            sourceStartUs = 1_000_000L,
            sourceMaxDurationUs = 15_000_000L
        )
        val clip2 = Clip(
            id = "c2",
            uri = "procedural://2",
            name = "Clip 2",
            timelineStartUs = 3_000_000L,
            durationUs = 4_000_000L,
            sourceStartUs = 2_000_000L,
            sourceMaxDurationUs = 15_000_000L
        )
        val clip3 = Clip(
            id = "c3",
            uri = "procedural://3",
            name = "Clip 3",
            timelineStartUs = 7_000_000L,
            durationUs = 3_000_000L,
            sourceStartUs = 1_000_000L,
            sourceMaxDurationUs = 15_000_000L
        )
        return TimelineState(
            tracks = listOf(
                Track(id = "v1", name = "V1", type = TrackType.VIDEO, clips = listOf(clip1, clip2, clip3))
            )
        )
    }

    @Test
    fun `AddClipCommand resolves magnetic collisions and restores exact state on undo and redo`() {
        val initial = createInitialThreeClipState()
        val manager = CommandManager(initial)

        val overlappingClip = Clip(
            id = "c4",
            uri = "procedural://4",
            name = "Clip 4",
            timelineStartUs = 1_000_000L,
            durationUs = 2_000_000L
        )
        val afterAdd = manager.execute(AddClipCommand("v1", overlappingClip, resolveCollisionsMagnetically = true))
        val added = afterAdd.findClip("c4")
        assertNotNull(added)
        // Should be magnetically placed at the end of existing contiguous clips (10,000,000 us)
        assertEquals(10_000_000L, added!!.timelineStartUs)

        val afterUndo = manager.undo()
        assertEquals(initial, afterUndo)

        val afterRedo = manager.redo()
        assertEquals(afterAdd, afterRedo)
    }

    @Test
    fun `BladeSplitClipCommand preserves total duration and properly offsets second clip sourceStartUs`() {
        val initial = createInitialThreeClipState()
        val manager = CommandManager(initial)

        // Split c2 (starts at 3.0s, duration 4.0s, sourceStartUs 2.0s) at 4.5s (1.5s into clip)
        val splitCmd = BladeSplitClipCommand(
            clipId = "c2",
            splitTimestampUs = 4_500_000L,
            rightClipId = "c2_right"
        )
        val afterSplit = manager.execute(splitCmd)

        val left = afterSplit.findClip("c2")!!
        val right = afterSplit.findClip("c2_right")!!

        assertEquals(1_500_000L, left.durationUs)
        assertEquals(2_500_000L, right.durationUs)
        assertEquals(4_000_000L, left.durationUs + right.durationUs)
        assertEquals(4_500_000L, right.timelineStartUs)
        assertEquals(3_500_000L, right.sourceStartUs) // 2.0s + 1.5s = 3.5s

        assertEquals(initial, manager.undo())
        assertEquals(afterSplit, manager.redo())
    }

    @Test
    fun `RippleTrimCommand shifts all downstream clips on same track by exact trim delta without overlap`() {
        val initial = createInitialThreeClipState()
        val manager = CommandManager(initial)

        // Trim out-point of c1 by -500,000 us (shorten by 0.5s)
        val afterRipple = manager.execute(
            RippleTrimCommand(clipId = "c1", trimStart = false, deltaUs = -500_000L)
        )

        val c1 = afterRipple.findClip("c1")!!
        val c2 = afterRipple.findClip("c2")!!
        val c3 = afterRipple.findClip("c3")!!

        assertEquals(2_500_000L, c1.durationUs)
        assertEquals(2_500_000L, c2.timelineStartUs)
        assertEquals(6_500_000L, c3.timelineStartUs)

        assertEquals(initial, manager.undo())
        assertEquals(afterRipple, manager.redo())
    }

    @Test
    fun `RollEditCommand, SlipEditCommand, SlideEditCommand, and JLCutAudioOffsetCommand execute and undo bit-for-bit`() {
        val initial = createInitialThreeClipState()
        val manager = CommandManager(initial)

        // 1. Roll Edit between c1 and c2 by +500,000 us
        val afterRoll = manager.execute(RollEditCommand("c1", "c2", 500_000L))
        assertEquals(3_500_000L, afterRoll.findClip("c1")!!.durationUs)
        assertEquals(3_500_000L, afterRoll.findClip("c2")!!.timelineStartUs)
        assertEquals(3_500_000L, afterRoll.findClip("c2")!!.durationUs)
        assertEquals(2_500_000L, afterRoll.findClip("c2")!!.sourceStartUs)
        // Overall track duration remains 10.0s
        assertEquals(10_000_000L, afterRoll.findClip("c3")!!.timelineEndUs)

        // 2. Slip Edit on c2 by +400,000 us
        val afterSlip = manager.execute(SlipEditCommand("c2", 400_000L))
        assertEquals(2_900_000L, afterSlip.findClip("c2")!!.sourceStartUs)
        assertEquals(3_500_000L, afterSlip.findClip("c2")!!.durationUs)

        // 3. Slide Edit on c2 by -300,000 us
        val afterSlide = manager.execute(SlideEditCommand("c2", -300_000L))
        assertEquals(3_200_000L, afterSlide.findClip("c1")!!.durationUs)
        assertEquals(3_200_000L, afterSlide.findClip("c2")!!.timelineStartUs)
        assertEquals(6_700_000L, afterSlide.findClip("c3")!!.timelineStartUs)
        assertEquals(3_300_000L, afterSlide.findClip("c3")!!.durationUs)

        // 4. J/L Cut Audio Offset on c2 by -250,000 us (J-cut)
        val afterJl = manager.execute(JLCutAudioOffsetCommand("c2", -250_000L))
        assertEquals(-250_000L, afterJl.findClip("c2")!!.audioOffsetUs)
        assertEquals(2_950_000L, afterJl.findClip("c2")!!.audioTimelineStartUs)

        // Undo all 4 steps and verify exact bit-for-bit restoration
        assertEquals(afterSlide, manager.undo())
        assertEquals(afterSlip, manager.undo())
        assertEquals(afterRoll, manager.undo())
        assertEquals(initial, manager.undo())

        // Redo all 4 steps and verify exact bit-for-bit restoration
        assertEquals(afterRoll, manager.redo())
        assertEquals(afterSlip, manager.redo())
        assertEquals(afterSlide, manager.redo())
        assertEquals(afterJl, manager.redo())
    }
}
