package com.proedit.nle.data.local

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.TimelineState
import com.proedit.nle.domain.model.TrackType
import com.proedit.nle.domain.model.VideoResolution
import com.proedit.nle.media.extractor.MediaImportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ProjectSummary(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val resolutionLabel: String,
    val fps: Double,
    val durationUs: Long,
    val clipCount: Int,
    val thumbnailPath: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

class ProjectRepository(
    private val context: Context,
    private val projectDao: ProjectDao = ProEditDatabase.getInstance(context).projectDao()
) {
    private val appContext = context.applicationContext

    val allProjects: Flow<List<ProjectSummary>> = projectDao.observeAllProjects().map { list ->
        list.map { entity ->
            ProjectSummary(
                id = entity.id,
                name = entity.name,
                width = entity.width,
                height = entity.height,
                resolutionLabel = entity.resolutionLabel,
                fps = entity.fps,
                durationUs = entity.durationUs,
                clipCount = entity.clipCount,
                thumbnailPath = entity.thumbnailPath,
                createdAtMs = entity.createdAtMs,
                updatedAtMs = entity.updatedAtMs
            )
        }
    }

    suspend fun createProject(
        name: String,
        resolution: VideoResolution,
        fps: Double,
        initialMediaUris: List<Uri> = emptyList()
    ): ProjectSummary = withContext(Dispatchers.IO) {
        val projectId = "proj_${UUID.randomUUID().toString().take(8)}"
        val cleanName = name.trim().ifBlank { "Untitled Project" }
        var timeline = TimelineState.createEmptyProjectTimeline(
            id = projectId,
            name = cleanName,
            resolution = resolution,
            fps = fps
        )

        var thumbnailPath: String? = null
        if (initialMediaUris.isNotEmpty()) {
            var cursorUs = 0L
            val importedClips = mutableListOf<Clip>()
            for (uri in initialMediaUris) {
                val imported = MediaImportManager.importMediaToProject(
                    context = appContext,
                    projectId = projectId,
                    sourceUri = uri
                ) ?: continue

                if (thumbnailPath == null && imported.thumbnailBitmap != null) {
                    thumbnailPath = saveProjectThumbnail(projectId, imported.thumbnailBitmap)
                }

                val clip = Clip(
                    id = "clip_${UUID.randomUUID().toString().take(8)}",
                    uri = imported.localUriString,
                    name = imported.displayName,
                    timelineStartUs = cursorUs,
                    durationUs = imported.durationUs,
                    sourceStartUs = 0L,
                    sourceMaxDurationUs = imported.durationUs,
                    proceduralSeed = imported.displayName.hashCode()
                )
                importedClips.add(clip)
                cursorUs += imported.durationUs
            }

            if (importedClips.isNotEmpty()) {
                timeline = timeline.copy(
                    tracks = timeline.tracks.map { track ->
                        if (track.id == "track_v1") track.copy(clips = importedClips) else track
                    },
                    selectedClipId = importedClips.first().id,
                    selectedTrackId = "track_v1"
                )
            }
        }

        val now = System.currentTimeMillis()
        val entity = ProjectEntity(
            id = projectId,
            name = cleanName,
            width = resolution.width,
            height = resolution.height,
            resolutionLabel = resolution.label,
            fps = fps,
            durationUs = timeline.totalDurationUs,
            clipCount = timeline.totalClipCount,
            thumbnailPath = thumbnailPath,
            timelineJson = TimelineJsonCodec.encode(timeline),
            createdAtMs = now,
            updatedAtMs = now
        )
        projectDao.upsertProject(entity)

        ProjectSummary(
            id = entity.id,
            name = entity.name,
            width = entity.width,
            height = entity.height,
            resolutionLabel = entity.resolutionLabel,
            fps = entity.fps,
            durationUs = entity.durationUs,
            clipCount = entity.clipCount,
            thumbnailPath = entity.thumbnailPath,
            createdAtMs = entity.createdAtMs,
            updatedAtMs = entity.updatedAtMs
        )
    }

    suspend fun loadProjectTimeline(projectId: String): TimelineState? = withContext(Dispatchers.IO) {
        val entity = projectDao.getProjectById(projectId) ?: return@withContext null
        runCatching { TimelineJsonCodec.decode(entity.timelineJson) }.getOrNull()
    }

    suspend fun saveProjectTimeline(state: TimelineState) = withContext(Dispatchers.IO) {
        val existing = projectDao.getProjectById(state.id)
        val now = System.currentTimeMillis()
        var thumbPath = existing?.thumbnailPath

        if (thumbPath == null) {
            val firstVideoClip = state.tracks
                .filter { it.type == TrackType.VIDEO }
                .flatMap { it.clips }
                .firstOrNull()
            if (firstVideoClip != null) {
                val bmp = MediaImportManager.extractThumbnailBitmap(appContext, firstVideoClip.uri, 500_000L)
                if (bmp != null) {
                    thumbPath = saveProjectThumbnail(state.id, bmp)
                }
            }
        }

        val entity = ProjectEntity(
            id = state.id,
            name = state.name,
            width = state.resolution.width,
            height = state.resolution.height,
            resolutionLabel = state.resolution.label,
            fps = state.fps,
            durationUs = state.totalDurationUs,
            clipCount = state.totalClipCount,
            thumbnailPath = thumbPath,
            timelineJson = TimelineJsonCodec.encode(state),
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now
        )
        projectDao.upsertProject(entity)
    }

    suspend fun renameProject(projectId: String, newName: String) = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@withContext
        val existing = projectDao.getProjectById(projectId) ?: return@withContext
        val decoded = runCatching { TimelineJsonCodec.decode(existing.timelineJson) }.getOrNull()
        val updatedJson = if (decoded != null) {
            TimelineJsonCodec.encode(decoded.copy(name = trimmed))
        } else {
            existing.timelineJson
        }
        projectDao.upsertProject(
            existing.copy(
                name = trimmed,
                timelineJson = updatedJson,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun duplicateProject(projectId: String) = withContext(Dispatchers.IO) {
        val existing = projectDao.getProjectById(projectId) ?: return@withContext
        val newId = "proj_${UUID.randomUUID().toString().take(8)}"
        val copyName = "${existing.name} Copy"
        val decoded = runCatching { TimelineJsonCodec.decode(existing.timelineJson) }.getOrNull()
        val updatedJson = if (decoded != null) {
            TimelineJsonCodec.encode(decoded.copy(id = newId, name = copyName))
        } else {
            existing.timelineJson
        }
        val now = System.currentTimeMillis()
        projectDao.upsertProject(
            existing.copy(
                id = newId,
                name = copyName,
                timelineJson = updatedJson,
                createdAtMs = now,
                updatedAtMs = now
            )
        )
    }

    suspend fun deleteProject(projectId: String) = withContext(Dispatchers.IO) {
        projectDao.deleteProjectById(projectId)
        val projDir = File(appContext.filesDir, "projects/$projectId")
        if (projDir.exists()) {
            projDir.deleteRecursively()
        }
    }

    private fun saveProjectThumbnail(projectId: String, bitmap: Bitmap): String? {
        return runCatching {
            val dir = File(appContext.filesDir, "projects/$projectId").apply { mkdirs() }
            val file = File(dir, "thumb.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        }.getOrNull()
    }
}
