package com.proedit.nle.ui.projects

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.proedit.nle.data.local.ProjectRepository
import com.proedit.nle.data.local.ProjectSummary
import com.proedit.nle.domain.model.VideoResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProjectsViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)

    val projects: StateFlow<List<ProjectSummary>> = repository.allProjects
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    fun createProject(
        name: String,
        resolution: VideoResolution,
        fps: Double,
        initialMediaUris: List<Uri>,
        onCreated: (String) -> Unit
    ) {
        if (_isCreating.value) return
        viewModelScope.launch {
            _isCreating.value = true
            try {
                val summary = repository.createProject(
                    name = name,
                    resolution = resolution,
                    fps = fps,
                    initialMediaUris = initialMediaUris
                )
                onCreated(summary.id)
            } finally {
                _isCreating.value = false
            }
        }
    }

    fun renameProject(projectId: String, newName: String) {
        viewModelScope.launch {
            repository.renameProject(projectId, newName)
        }
    }

    fun duplicateProject(projectId: String) {
        viewModelScope.launch {
            repository.duplicateProject(projectId)
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
        }
    }
}
