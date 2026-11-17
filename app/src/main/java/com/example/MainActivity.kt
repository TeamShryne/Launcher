package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.proedit.nle.ui.editor.EditorScreen
import com.proedit.nle.ui.projects.ProjectsScreen
import com.proedit.nle.ui.theme.ProEditNleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProEditNleTheme {
                var activeProjectId by rememberSaveable { mutableStateOf<String?>(null) }

                val currentProjectId = activeProjectId
                if (currentProjectId == null) {
                    ProjectsScreen(
                        onOpenProject = { projectId -> activeProjectId = projectId }
                    )
                } else {
                    EditorScreen(
                        projectId = currentProjectId,
                        onBackToProjects = { activeProjectId = null }
                    )
                }
            }
        }
    }
}
