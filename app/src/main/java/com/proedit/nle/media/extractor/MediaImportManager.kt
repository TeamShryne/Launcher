package com.proedit.nle.media.extractor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ImportedMediaMetadata(
    val localUriString: String,
    val displayName: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    val thumbnailBitmap: Bitmap?
)

object MediaImportManager {

    /**
     * Copies a picked [sourceUri] into durable project-private storage (`filesDir/projects/<projectId>/media/`)
     * and probes real duration, dimensions, audio presence, display name, and an initial thumbnail.
     */
    suspend fun importMediaToProject(
        context: Context,
        projectId: String,
        sourceUri: Uri
    ): ImportedMediaMetadata? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, sourceUri) ?: "Clip_${System.currentTimeMillis() % 10000}"
        val mimeType = resolver.getType(sourceUri)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: displayName.substringAfterLast('.', "mp4").take(6)

        val mediaDir = File(context.filesDir, "projects/$projectId/media").apply { mkdirs() }
        val destFile = File(mediaDir, "media_${UUID.randomUUID().toString().take(8)}.$ext")

        val copied = runCatching {
            resolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } != null
        }.getOrDefault(false)

        val targetUriString = if (copied && destFile.length() > 0L) {
            Uri.fromFile(destFile).toString()
        } else {
            runCatching {
                resolver.takePersistableUriPermission(
                    sourceUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            sourceUri.toString()
        }

        probeMediaMetadata(context, targetUriString, displayName)
    }

    fun probeMediaMetadata(
        context: Context,
        uriString: String,
        fallbackName: String
    ): ImportedMediaMetadata? {
        val uri = Uri.parse(uriString)
        val retriever = MediaMetadataRetriever()
        var durationUs = 0L
        var width = 1920
        var height = 1080
        var hasAudio = false
        var thumb: Bitmap? = null

        try {
            if (uri.scheme == "file" && uri.path != null) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            durationUs = (durationMs * 1000L).coerceAtLeast(100_000L)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null

            val rawThumb = retriever.getFrameAtTime(
                minOf(500_000L, durationUs / 3L),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            if (rawThumb != null) {
                thumb = Bitmap.createScaledBitmap(rawThumb, 320, 180, true)
                if (rawThumb != thumb) rawThumb.recycle()
            }
        } catch (_: Exception) {
            // Fall back to MediaExtractor probe for audio-only or container edge cases
        } finally {
            runCatching { retriever.release() }
        }

        if (durationUs <= 100_000L) {
            val extractor = MediaExtractor()
            try {
                if (uri.scheme == "file" && uri.path != null) {
                    extractor.setDataSource(uri.path!!)
                } else {
                    extractor.setDataSource(context, uri, null)
                }
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = maxOf(durationUs, fmt.getLong(MediaFormat.KEY_DURATION))
                    }
                    if (mime.startsWith("audio/")) {
                        hasAudio = true
                    }
                }
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { extractor.release() }
            }
        }

        if (durationUs <= 0L) return null

        return ImportedMediaMetadata(
            localUriString = uriString,
            displayName = fallbackName,
            durationUs = durationUs,
            width = width,
            height = height,
            hasAudio = hasAudio,
            thumbnailBitmap = thumb
        )
    }

    fun extractThumbnailBitmap(
        context: Context,
        uriString: String,
        timestampUs: Long
    ): Bitmap? {
        val uri = Uri.parse(uriString)
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file" && uri.path != null) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            val raw = retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val scaled = Bitmap.createScaledBitmap(raw, 320, 180, true)
            if (raw != scaled) raw.recycle()
            scaled
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull()
    }
}
