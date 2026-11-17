package com.proedit.nle.media.extractor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.proedit.nle.domain.model.Clip
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FrameThumbnailExtractor(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val bitmapCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val composeImageCache: LruCache<String, ImageBitmap> = LruCache(160)

    fun getCachedThumbnail(clipId: String, bucketIndex: Int): ImageBitmap? {
        val key = "${clipId}_$bucketIndex"
        composeImageCache.get(key)?.let { return it }
        val bmp = bitmapCache.get(key) ?: return null
        val img = bmp.asImageBitmap()
        composeImageCache.put(key, img)
        return img
    }

    suspend fun extractThumbnailStrip(
        clip: Clip,
        bucketCount: Int = 8,
        width: Int = 128,
        height: Int = 72
    ): Int = withContext(dispatcher) {
        var extracted = 0
        val uri = Uri.parse(clip.uri)
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file" && uri.path != null) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }

            val stepUs = (clip.durationUs / bucketCount.coerceAtLeast(1)).coerceAtLeast(1L)
            for (i in 0 until bucketCount) {
                val key = "${clip.id}_$i"
                if (bitmapCache.get(key) != null) {
                    extracted++
                    continue
                }
                val targetUs = (clip.sourceStartUs + i * stepUs + stepUs / 2L)
                    .coerceIn(0L, clip.sourceMaxDurationUs)
                val raw = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (raw != null) {
                    val scaled = Bitmap.createScaledBitmap(raw, width, height, true)
                    if (raw != scaled) raw.recycle()
                    bitmapCache.put(key, scaled)
                    composeImageCache.put(key, scaled.asImageBitmap())
                    extracted++
                }
            }
        } catch (_: Exception) {
            // Media may be audio-only or inaccessible
        } finally {
            runCatching { retriever.release() }
        }
        extracted
    }

    suspend fun extractSingleFrame(
        uriString: String,
        timestampUs: Long,
        width: Int = 320,
        height: Int = 180
    ): Bitmap? = withContext(dispatcher) {
        val uri = Uri.parse(uriString)
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file" && uri.path != null) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            val raw = retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withContext null
            val scaled = Bitmap.createScaledBitmap(raw, width, height, true)
            if (raw != scaled) raw.recycle()
            scaled
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
