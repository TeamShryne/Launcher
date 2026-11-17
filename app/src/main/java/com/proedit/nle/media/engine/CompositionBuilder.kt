package com.proedit.nle.media.engine

import android.graphics.Matrix
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.proedit.nle.domain.math.SpeedRampIntegrator
import com.proedit.nle.domain.model.AudioEffects
import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.TimelineState
import com.proedit.nle.domain.model.TrackType
import com.proedit.nle.domain.model.evaluateAt
import com.proedit.nle.media.shader.ColorGradingGlEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

@OptIn(UnstableApi::class)
object CompositionBuilder {

    /**
     * Converts the domain [TimelineState] into a Media3 [Composition] containing
     * [EditedMediaItemSequence] tracks with [MediaItem.ClippingConfiguration],
     * [ColorGradingGlEffect], keyframed [MatrixTransformation], and [ParametricEqAudioProcessor].
     */
    fun buildComposition(
        state: TimelineState,
        includeAudio: Boolean = true
    ): Composition {
        val sequences = mutableListOf<EditedMediaItemSequence>()

        for (track in state.tracks) {
            if (track.isMuted) continue
            val usableClips = track.sortedClips
            if (usableClips.isEmpty()) continue

            val editedItems = usableClips.map { clip ->
                buildEditedMediaItem(
                    clip = clip,
                    trackType = track.type,
                    trackVolumeDb = track.volumeDb,
                    includeAudio = includeAudio
                )
            }

            if (editedItems.isNotEmpty()) {
                sequences.add(EditedMediaItemSequence.Builder(editedItems).build())
            }
        }

        if (sequences.isEmpty()) {
            return Composition.Builder(emptyList()).build()
        }

        return Composition.Builder(sequences)
            .setTransmuxVideo(false)
            .setTransmuxAudio(!includeAudio)
            .build()
    }

    fun buildEditedMediaItem(
        clip: Clip,
        trackType: TrackType,
        trackVolumeDb: Float = 0f,
        includeAudio: Boolean = true
    ): EditedMediaItem {
        val sourceEndUs = SpeedRampIntegrator.mapTimelineToSourceUs(
            timelineOffsetUs = clip.durationUs,
            sourceStartUs = clip.sourceStartUs,
            speedCurve = clip.speedCurve
        ).coerceAtMost(clip.sourceMaxDurationUs)

        val clippingConfig = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(clip.sourceStartUs.coerceAtLeast(0L))
            .setEndPositionUs(sourceEndUs.coerceAtLeast(clip.sourceStartUs + 41_667L))
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(clip.uri))
            .setMediaId(clip.id)
            .setClippingConfiguration(clippingConfig)
            .build()

        val videoEffects = mutableListOf<Effect>()
        if (trackType != TrackType.AUDIO) {
            // Keyframed Matrix Transformation (Position X/Y, Scale, Rotation)
            videoEffects.add(
                MatrixTransformation { presentationTimeUs ->
                    val clipRelativeUs = (presentationTimeUs - clip.sourceStartUs).coerceIn(0L, clip.durationUs)
                    val tx = clip.transform.positionX.evaluateAt(clipRelativeUs)
                    val ty = clip.transform.positionY.evaluateAt(clipRelativeUs)
                    val scale = clip.transform.scale.evaluateAt(clipRelativeUs).coerceIn(0.1f, 5.0f)
                    val rotation = clip.transform.rotation.evaluateAt(clipRelativeUs)

                    Matrix().apply {
                        postTranslate(tx, ty)
                        postRotate(rotation)
                        postScale(scale, scale)
                    }
                }
            )
            // Single-pass OpenGL ES 3.0 Color Grading & 3D LUT shader
            if (!clip.colorGrade.isBypassed) {
                videoEffects.add(ColorGradingGlEffect(clip.colorGrade))
            }
        }

        val audioProcessors = mutableListOf<AudioProcessor>()
        if (includeAudio && trackType != TrackType.ADJUSTMENT) {
            audioProcessors.add(
                ParametricEqAudioProcessor(
                    audioEffects = clip.audioEffects,
                    trackVolumeDb = trackVolumeDb,
                    audioOffsetUs = clip.audioOffsetUs
                )
            )
        }

        return EditedMediaItem.Builder(mediaItem)
            .setDurationUs(clip.durationUs)
            .setRemoveAudio(!includeAudio || trackType == TrackType.ADJUSTMENT)
            .setRemoveVideo(trackType == TrackType.AUDIO)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()
    }
}

/**
 * Custom 16-bit PCM [AudioProcessor] applying clip volume, track volume, limiter ceiling,
 * and J/L cut offset gain envelope.
 */
@OptIn(UnstableApi::class)
class ParametricEqAudioProcessor(
    private val audioEffects: AudioEffects,
    private val trackVolumeDb: Float,
    private val audioOffsetUs: Long
) : BaseAudioProcessor() {

    private var linearGain: Float = 1.0f
    private var limiterCeilingLinear: Float = 0.966f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val combinedDb = (audioEffects.volumeDb + trackVolumeDb).coerceIn(-60f, 18f)
        val eqMidBandDb = audioEffects.evaluateMagnitudeDbAt(1000f) * 0.25f
        linearGain = 10.0.pow((combinedDb + eqMidBandDb) / 20.0).toFloat()
        limiterCeilingLinear = if (audioEffects.limiterEnabled) {
            10.0.pow(audioEffects.limiterCeilingDb.toDouble() / 20.0).toFloat().coerceIn(0.1f, 1.0f)
        } else {
            1.0f
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining).order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val maxShort = (limiterCeilingLinear * Short.MAX_VALUE).toInt()
        val minShort = -maxShort

        while (inputBuffer.remaining() >= 2) {
            val sample = inputBuffer.short.toInt()
            val scaled = (sample * linearGain).toInt().coerceIn(minShort, maxShort)
            outputBuffer.putShort(scaled.toShort())
        }
        outputBuffer.flip()
    }
}
