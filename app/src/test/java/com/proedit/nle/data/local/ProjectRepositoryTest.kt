package com.proedit.nle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.VideoResolution
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProjectRepositoryTest {

    private lateinit var db: ProEditDatabase
    private lateinit var repository: ProjectRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ProEditDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProjectRepository(context, db.projectDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `create, save, rename, duplicate, and delete project lifecycle persists accurately in Room`() = runBlocking {
        // 1. Create project
        val created = repository.createProject(
            name = "Documentary Cut",
            resolution = VideoResolution.FHD_1080P,
            fps = 24.0
        )
        assertEquals("Documentary Cut", created.name)
        assertEquals(0L, created.durationUs)
        assertEquals(0, created.clipCount)

        // 2. Load timeline & add a real clip, then save
        val loaded = repository.loadProjectTimeline(created.id)
        assertNotNull(loaded)
        val clip = Clip(
            id = "clip_real_1",
            uri = "file:///data/user/0/com.proedit.nle/files/projects/${created.id}/media/scene1.mp4",
            name = "scene1.mp4",
            timelineStartUs = 0L,
            durationUs = 6_000_000L,
            sourceStartUs = 0L,
            sourceMaxDurationUs = 6_000_000L
        )
        val edited = loaded!!.copy(
            tracks = loaded.tracks.map { t ->
                if (t.id == "track_v1") t.copy(clips = listOf(clip)) else t
            }
        )
        repository.saveProjectTimeline(edited)

        val afterSaveList = repository.allProjects.first()
        assertEquals(1, afterSaveList.size)
        assertEquals(6_000_000L, afterSaveList[0].durationUs)
        assertEquals(1, afterSaveList[0].clipCount)

        // 3. Rename project
        repository.renameProject(created.id, "Director Cut")
        val renamedTimeline = repository.loadProjectTimeline(created.id)!!
        assertEquals("Director Cut", renamedTimeline.name)

        // 4. Duplicate project
        repository.duplicateProject(created.id)
        val afterDupList = repository.allProjects.first()
        assertEquals(2, afterDupList.size)
        val copySummary = afterDupList.first { it.id != created.id }
        assertEquals("Director Cut Copy", copySummary.name)
        assertEquals(1, copySummary.clipCount)

        // 5. Delete original project
        repository.deleteProject(created.id)
        assertNull(repository.loadProjectTimeline(created.id))
        assertEquals(1, repository.allProjects.first().size)
    }
}
