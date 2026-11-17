package com.proedit.nle.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val resolutionLabel: String,
    val fps: Double,
    val durationUs: Long,
    val clipCount: Int,
    val thumbnailPath: String?,
    val timelineJson: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)
