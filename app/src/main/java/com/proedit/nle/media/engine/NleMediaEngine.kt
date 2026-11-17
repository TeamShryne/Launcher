package com.proedit.nle.media.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.proedit.nle.domain.math.SpeedRampIntegrator
import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.ColorGrade
import com.proedit.nle.domain.model.TimelineState
import com.proedit.nle.media.shader.ColorGradingGlEffect
import kotlin.math.abs

/**
 * Manages [ExoPlayer] hardware video/audio preview, [TextureView] rendering (so OpenGL ES 3.0
 * graded frames can be captured directly for real-time Video Scopes), and frame-accurate scrubbing.
 */
@OptIn(UnstableApi::class)
class NleMediaEngine(
    context: Context
) {
    private val appContext = context.applicationContext

    val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(appContext)
            .setSeekParameters(SeekParameters.EXACT)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = false
            }
    }

    val audioPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(appContext)
            .setSeekParameters(SeekParameters.EXACT)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = false
            }
    }

    private var videoTextureView: TextureView? = null

    private var boundVideoClipId: String? = null
    private var boundVideoUri: String? = null
    private var boundVideoSourceStartUs: Long = -1L
    private var boundVideoDurationUs: Long = -1L
    private var boundColorGrade: ColorGrade? = null
    private var boundSpeed: Float = 1.0f

    private var boundAudioClipId: String? = null
    private var boundAudioUri: String? = null
    private var boundAudioStartUs: Long = -1L
    private var boundAudioDurationUs: Long = -1L
    private var lastSyncedPlayheadUs: Long = -1L

    fun attachVideoTextureView(textureView: TextureView?) {
        if (videoTextureView === textureView) return
        videoTextureView = textureView
        exoPlayer.setVideoTextureView(textureView)
    }

    /**
     * Captures the live OpenGL-graded video frame from the attached [TextureView] for real-time Scopes.
     */
    fun captureLiveFrameBitmap(width: Int = 128, height: Int = 72): Bitmap? {
        val tv = videoTextureView ?: return null
        if (!tv.isAvailable) return null
        return runCatching { tv.getBitmap(width, height) }.getOrNull()
    }

    /**
     * Synchronizes [exoPlayer] and [audioPlayer] with the domain [TimelineState].
     * Only rebinds media items or OpenGL effects when the active clip, trim bounds, or color grade change.
     */
    fun syncWithTimeline(state: TimelineState, isPlaying: Boolean) {
        val activeVideoClip = state.activeVideoClipAt(state.playheadPositionUs)
        syncVideoPlayer(activeVideoClip, state.playheadPositionUs, isPlaying)

        val activeAudioPair = state.activeAudioClipsAt(state.playheadPositionUs).firstOrNull()
        syncAudioPlayer(activeAudioPair?.second, state.playheadPositionUs, isPlaying)

        lastSyncedPlayheadUs = state.playheadPositionUs
    }

    /**
     * Maps the live [exoPlayer] playback position back to timeline microseconds during playback.
     */
    fun currentTimelinePositionUs(state: TimelineState): Long? {
        val activeClip = state.findClip(boundVideoClipId) ?: state.activeVideoClipAt(state.playheadPositionUs)
            ?: return null
        if (!exoPlayer.isPlaying) return null
        // With ClippingConfiguration, exoPlayer.currentPosition is relative to clip.sourceStartUs
        val offsetInClipUs = (exoPlayer.currentPosition * 1000L).coerceAtLeast(0L)
        return (activeClip.timelineStartUs + offsetInClipUs).coerceAtMost(activeClip.timelineEndUs)
    }

    private fun syncVideoPlayer(
        activeClip: Clip?,
        playheadUs: Long,
        isPlaying: Boolean
    ) {
        if (activeClip == null) {
            if (exoPlayer.isPlaying) exoPlayer.pause()
            return
        }

        val needsRebind = boundVideoClipId != activeClip.id ||
            boundVideoUri != activeClip.uri ||
            boundVideoSourceStartUs != activeClip.sourceStartUs ||
            boundVideoDurationUs != activeClip.durationUs

        val needsEffectUpdate = needsRebind || boundColorGrade != activeClip.colorGrade

        if (needsRebind) {
            bindClipToPlayer(exoPlayer, activeClip)
            boundVideoClipId = activeClip.id
            boundVideoUri = activeClip.uri
            boundVideoSourceStartUs = activeClip.sourceStartUs
            boundVideoDurationUs = activeClip.durationUs
        }

        if (needsEffectUpdate) {
            boundColorGrade = activeClip.colorGrade
            val effects = if (activeClip.colorGrade.isBypassed) {
                emptyList<Effect>()
            } else {
                listOf<Effect>(ColorGradingGlEffect(activeClip.colorGrade))
            }
            exoPlayer.setVideoEffects(effects)
            if (!needsRebind) {
                exoPlayer.prepare()
            }
        }

        val avgSpeed = SpeedRampIntegrator.averageSpeedMultiplier(
            durationUs = activeClip.durationUs,
            speedCurve = activeClip.speedCurve
        )
        if (abs(avgSpeed - boundSpeed) > 0.01f) {
            boundSpeed = avgSpeed
            exoPlayer.playbackParameters = PlaybackParameters(avgSpeed)
        }

        val clipRelativeMs = ((playheadUs - activeClip.timelineStartUs).coerceIn(0L, activeClip.durationUs)) / 1000L

        if (!isPlaying) {
            if (exoPlayer.isPlaying) exoPlayer.pause()
            if (needsRebind || abs(exoPlayer.currentPosition - clipRelativeMs) > 20L) {
                exoPlayer.seekTo(clipRelativeMs)
            }
        } else {
            val jumpedManually = abs(playheadUs - lastSyncedPlayheadUs) > 250_000L
            if (needsRebind || jumpedManually) {
                exoPlayer.seekTo(clipRelativeMs)
            }
            if (!exoPlayer.isPlaying) {
                exoPlayer.play()
            }
        }
    }

    private fun syncAudioPlayer(
        activeAudioClip: Clip?,
        playheadUs: Long,
        isPlaying: Boolean
    ) {
        if (activeAudioClip == null) {
            if (audioPlayer.isPlaying) audioPlayer.pause()
            return
        }

        val needsRebind = boundAudioClipId != activeAudioClip.id ||
            boundAudioUri != activeAudioClip.uri ||
            boundAudioStartUs != activeAudioClip.sourceStartUs ||
            boundAudioDurationUs != activeAudioClip.durationUs

        if (needsRebind) {
            bindClipToPlayer(audioPlayer, activeAudioClip)
            boundAudioClipId = activeAudioClip.id
            boundAudioUri = activeAudioClip.uri
            boundAudioStartUs = activeAudioClip.sourceStartUs
            boundAudioDurationUs = activeAudioClip.durationUs
        }

        val clipRelativeMs = ((playheadUs - activeAudioClip.audioTimelineStartUs)
            .coerceIn(0L, activeAudioClip.durationUs)) / 1000L

        if (!isPlaying) {
            if (audioPlayer.isPlaying) audioPlayer.pause()
            if (needsRebind || abs(audioPlayer.currentPosition - clipRelativeMs) > 40L) {
                audioPlayer.seekTo(clipRelativeMs)
            }
        } else {
            if (needsRebind) {
                audioPlayer.seekTo(clipRelativeMs)
            }
            if (!audioPlayer.isPlaying) {
                audioPlayer.play()
            }
        }
    }

    private fun bindClipToPlayer(player: ExoPlayer, clip: Clip) {
        val sourceEndUs = SpeedRampIntegrator.mapTimelineToSourceUs(
            timelineOffsetUs = clip.durationUs,
            sourceStartUs = clip.sourceStartUs,
            speedCurve = clip.speedCurve
        ).coerceAtMost(clip.sourceMaxDurationUs)
            .coerceAtLeast(clip.sourceStartUs + 41_667L)

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(clip.sourceStartUs.coerceAtLeast(0L))
            .setEndPositionUs(sourceEndUs)
            .build()

        val item = MediaItem.Builder()
            .setUri(Uri.parse(clip.uri))
            .setMediaId(clip.id)
            .setClippingConfiguration(clipping)
            .build()

        player.setMediaItem(item)
        player.prepare()
    }

    fun release() {
        runCatching { exoPlayer.setVideoTextureView(null) }
        runCatching { exoPlayer.release() }
        runCatching { audioPlayer.release() }
    }
}
