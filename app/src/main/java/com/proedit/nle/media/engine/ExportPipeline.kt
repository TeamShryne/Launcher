package com.proedit.nle.media.engine

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.proedit.nle.domain.model.ExportConfig
import com.proedit.nle.domain.model.TimelineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

sealed interface ExportStatus {
    data object Idle : ExportStatus
    data class InProgress(
        val progress: Float,
        val renderedFrames: Int,
        val totalFrames: Int,
        val elapsedMs: Long,
        val outputPath: String
    ) : ExportStatus

    data class Completed(
        val outputPath: String,
        val elapsedMs: Long,
        val fileSizeBytes: Long
    ) : ExportStatus

    data class Failed(val errorMessage: String) : ExportStatus
    data object Cancelled : ExportStatus
}

@OptIn(UnstableApi::class)
class ExportPipeline(
    private val context: Context
) {
    private var activeTransformer: Transformer? = null

    /**
     * Exports the real [TimelineState] composition to an MP4 file using Media3 [Transformer].
     */
    fun exportTimeline(
        state: TimelineState,
        config: ExportConfig
    ): Flow<ExportStatus> = flow {
        val allClips = state.tracks.flatMap { it.clips }
        if (allClips.isEmpty()) {
            emit(ExportStatus.Failed("Timeline is empty. Add at least one video or audio clip to export."))
            return@flow
        }

        val startTimeMs = System.currentTimeMillis()
        val outputDir = File(context.filesDir, "exports").apply { mkdirs() }
        val safeName = config.outputFileName.ifBlank { "Export_${System.currentTimeMillis()}.mp4" }
        val outputFile = File(outputDir, safeName)
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val totalFrames = ((state.totalDurationUs / 1_000_000.0) * config.fps).toInt().coerceAtLeast(1)
        val composition = CompositionBuilder.buildComposition(
            state = state,
            includeAudio = config.includeAudio
        )

        var isDone = false
        var exportError: ExportException? = null

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(config.videoCodec.mimeType)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        isDone = true
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        exportError = exportException
                        isDone = true
                    }
                })
                .build()
            activeTransformer = transformer
            transformer.start(composition, outputFile.absolutePath)
        }

        val progressHolder = ProgressHolder()
        while (!isDone) {
            var currentPct = 0f
            mainHandler.post {
                val stateCode = activeTransformer?.getProgress(progressHolder)
                if (stateCode == Transformer.PROGRESS_STATE_AVAILABLE) {
                    currentPct = progressHolder.progress / 100f
                }
            }
            val elapsed = System.currentTimeMillis() - startTimeMs
            val framesDone = (currentPct * totalFrames).toInt()
            emit(
                ExportStatus.InProgress(
                    progress = currentPct.coerceIn(0f, 0.99f),
                    renderedFrames = framesDone,
                    totalFrames = totalFrames,
                    elapsedMs = elapsed,
                    outputPath = outputFile.absolutePath
                )
            )
            delay(60L)
        }

        if (exportError != null) {
            emit(ExportStatus.Failed(exportError?.localizedMessage ?: "Export failed"))
            return@flow
        }

        val totalElapsed = System.currentTimeMillis() - startTimeMs
        emit(
            ExportStatus.Completed(
                outputPath = outputFile.absolutePath,
                elapsedMs = totalElapsed,
                fileSizeBytes = outputFile.length()
            )
        )
    }

    fun cancelExport() {
        Handler(Looper.getMainLooper()).post {
            activeTransformer?.cancel()
            activeTransformer = null
        }
    }

    /**
     * Copies the exported MP4 file into the system Movies/ProEdit gallery collection via MediaStore.
     */
    suspend fun saveExportToGallery(filePath: String): Boolean = withContext(Dispatchers.IO) {
        val srcFile = File(filePath)
        if (!srcFile.exists()) return@withContext false

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, srcFile.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ProEdit")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val itemUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext false

        return@withContext runCatching {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(srcFile).use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            }
            true
        }.getOrDefault(false)
    }
}
