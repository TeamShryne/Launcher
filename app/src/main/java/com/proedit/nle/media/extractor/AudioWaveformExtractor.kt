package com.proedit.nle.media.extractor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.LruCache
import com.proedit.nle.domain.model.Clip
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.sqrt

class AudioWaveformExtractor(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val waveformCache = LruCache<String, FloatArray>(64)

    fun getWaveformCached(clip: Clip, bucketCount: Int = 96): FloatArray? {
        return waveformCache.get("${clip.id}_$bucketCount")
    }

    /**
     * Decodes real 16-bit PCM audio samples across the clip using [MediaExtractor] + [MediaCodec]
     * and computes normalized RMS amplitude buckets. Returns an empty array if the clip has no audio.
     */
    suspend fun extractWaveformBuckets(
        clip: Clip,
        bucketCount: Int = 96
    ): FloatArray = withContext(dispatcher) {
        val key = "${clip.id}_$bucketCount"
        waveformCache.get(key)?.let { return@withContext it }

        val buckets = decodeRealAudioRms(clip, bucketCount) ?: FloatArray(0)
        waveformCache.put(key, buckets)
        buckets
    }

    private fun decodeRealAudioRms(
        clip: Clip,
        bucketCount: Int
    ): FloatArray? {
        val uri = Uri.parse(clip.uri)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            if (uri.scheme == "file" && uri.path != null) {
                extractor.setDataSource(uri.path!!)
            } else {
                extractor.setDataSource(context, uri, null)
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) return null

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(audioTrackIndex)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            val rmsBuckets = FloatArray(bucketCount)
            val spanUs = clip.durationUs.coerceAtLeast(100_000L)
            val stepUs = spanUs / bucketCount.coerceAtLeast(1)
            val info = MediaCodec.BufferInfo()

            for (bucketIdx in 0 until bucketCount) {
                val seekUs = (clip.sourceStartUs + bucketIdx * stepUs).coerceIn(0L, clip.sourceMaxDurationUs)
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()

                var sumSq = 0.0
                var sampleCount = 0
                var framesDecoded = 0

                while (framesDecoded < 3) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx) ?: break
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            break
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 5_000L)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val pcm = outBuf.order(ByteOrder.LITTLE_ENDIAN)
                            while (pcm.remaining() >= 2) {
                                val s = pcm.short.toDouble() / 32768.0
                                sumSq += s * s
                                sampleCount++
                            }
                            framesDecoded++
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        framesDecoded++
                    }
                }

                rmsBuckets[bucketIdx] = if (sampleCount > 0) {
                    sqrt(sumSq / sampleCount).toFloat()
                } else {
                    0.02f
                }
            }

            val maxPeak = (rmsBuckets.maxOrNull() ?: 0.05f).coerceAtLeast(0.05f)
            FloatArray(bucketCount) { i ->
                (rmsBuckets[i] / maxPeak).coerceIn(0.04f, 0.98f)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
