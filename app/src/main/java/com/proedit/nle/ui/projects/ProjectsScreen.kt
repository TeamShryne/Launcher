package com.proedit.nle.ui.projects

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.proedit.nle.data.local.ProjectSummary
import com.proedit.nle.domain.math.TimecodeCalculator
import com.proedit.nle.domain.model.TimelineState
import com.proedit.nle.domain.model.VideoResolution
import com.proedit.nle.ui.theme.AccentPrimary
import com.proedit.nle.ui.theme.AccentSecondary
import com.proedit.nle.ui.theme.BackgroundPrimary
import com.proedit.nle.ui.theme.BorderSubtle
import com.proedit.nle.ui.theme.SemanticRed
import com.proedit.nle.ui.theme.SurfaceDark
import com.proedit.nle.ui.theme.SurfaceElevated
import com.proedit.nle.ui.theme.TextPrimary
import com.proedit.nle.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProjectsScreen(
    onOpenProject: (String) -> Unit,
    viewModel: ProjectsViewModel = viewModel()
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()

    var showNewProjectDialog by remember { mutableStateOf(false) }
    var projectToRename by remember { mutableStateOf<ProjectSummary?>(null) }
    var projectToDelete by remember { mutableStateOf<ProjectSummary?>(null) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = BackgroundPrimary
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(BackgroundPrimary)
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .border(0.5.dp, BorderSubtle)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Projects",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "${projects.size} ${if (projects.size == 1) "project" else "projects"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                Row(
                    modifier = Modifier
                        .background(AccentPrimary, RoundedCornerShape(6.dp))
                        .clickable { showNewProjectDialog = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("new_project_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.cd_new_project),
                        tint = BackgroundPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "New Project",
                        style = MaterialTheme.typography.titleSmall,
                        color = BackgroundPrimary
                    )
                }
            }

            if (projects.isEmpty()) {
                // Clean Empty State
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "No projects yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Create a project to import footage and start editing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .background(AccentPrimary, RoundedCornerShape(6.dp))
                                .clickable { showNewProjectDialog = true }
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                                .testTag("empty_new_project_button")
                        ) {
                            Text(
                                text = "Create Project",
                                style = MaterialTheme.typography.titleSmall,
                                color = BackgroundPrimary
                            )
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 260.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("projects_grid"),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { onOpenProject(project.id) },
                            onRename = { projectToRename = project },
                            onDuplicate = { viewModel.duplicateProject(project.id) },
                            onDelete = { projectToDelete = project }
                        )
                    }
                }
            }
        }
    }

    if (showNewProjectDialog) {
        NewProjectDialog(
            isCreating = isCreating,
            suggestedName = "Project ${projects.size + 1}",
            onDismiss = { if (!isCreating) showNewProjectDialog = false },
            onCreate = { name, resolution, fps, uris ->
                viewModel.createProject(name, resolution, fps, uris) { createdId ->
                    showNewProjectDialog = false
                    onOpenProject(createdId)
                }
            }
        )
    }

    projectToRename?.let { target ->
        RenameProjectDialog(
            currentName = target.name,
            onDismiss = { projectToRename = null },
            onConfirm = { newName ->
                viewModel.renameProject(target.id, newName)
                projectToRename = null
            }
        )
    }

    projectToDelete?.let { target ->
        DeleteProjectDialog(
            projectName = target.name,
            onDismiss = { projectToDelete = null },
            onConfirm = {
                viewModel.deleteProject(target.id)
                projectToDelete = null
            }
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val thumbnailBitmap = remember(project.thumbnailPath) {
        val path = project.thumbnailPath
        if (path != null && File(path).exists()) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        } else {
            null
        }
    }

    val formattedDuration = remember(project.durationUs, project.fps) {
        TimecodeCalculator.formatSmpte(project.durationUs, project.fps, project.fps == 29.97)
    }
    val formattedDate = remember(project.updatedAtMs) {
        SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(project.updatedAtMs))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(8.dp))
            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .testTag("project_card_${project.id}")
    ) {
        // 16:9 Thumbnail Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(SurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailBitmap != null) {
                Image(
                    bitmap = thumbnailBitmap,
                    contentDescription = project.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = TextSecondary.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            }

            // Duration Badge
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(BackgroundPrimary.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formattedDuration,
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentPrimary
                )
            }
        }

        // Metadata Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${project.resolutionLabel}  •  ${formatFpsLabel(project.fps)}  •  ${project.clipCount} ${if (project.clipCount == 1) "clip" else "clips"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1
                )
                Text(
                    text = "Edited $formattedDate",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.7f)
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.testTag("project_menu_${project.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.cd_project_options),
                        tint = TextSecondary
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(SurfaceElevated)
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = TextPrimary) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TextPrimary) },
                        onClick = {
                            menuExpanded = false
                            onDuplicate()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = SemanticRed) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = SemanticRed) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewProjectDialog(
    isCreating: Boolean,
    suggestedName: String,
    onDismiss: () -> Unit,
    onCreate: (String, VideoResolution, Double, List<Uri>) -> Unit
) {
    var projectName by remember { mutableStateOf(suggestedName) }
    var selectedResolution by remember { mutableStateOf(VideoResolution.FHD_1080P) }
    var selectedFps by remember { mutableDoubleStateOf(30.0) }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val multiVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris = uris
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(10.dp))
                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .padding(16.dp)
                .testTag("new_project_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "New Project",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )

            OutlinedTextField(
                value = projectName,
                onValueChange = { projectName = it },
                label = { Text("Project name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("new_project_name_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPrimary,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedLabelColor = AccentPrimary,
                    unfocusedLabelColor = TextSecondary
                )
            )

            // Resolution / Aspect Ratio
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Resolution & Aspect", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VideoResolution.PRESETS.forEach { res ->
                        val isSelected = selectedResolution == res
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isSelected) AccentPrimary.copy(alpha = 0.2f) else SurfaceElevated,
                                    RoundedCornerShape(5.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) AccentPrimary else BorderSubtle,
                                    RoundedCornerShape(5.dp)
                                )
                                .clickable { selectedResolution = res }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = res.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) AccentPrimary else TextPrimary
                            )
                        }
                    }
                }
            }

            // Frame Rate
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Frame Rate", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TimelineState.SUPPORTED_FPS.forEach { fps ->
                        val isSelected = selectedFps == fps
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isSelected) AccentPrimary.copy(alpha = 0.2f) else SurfaceElevated,
                                    RoundedCornerShape(5.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) AccentPrimary else BorderSubtle,
                                    RoundedCornerShape(5.dp)
                                )
                                .clickable { selectedFps = fps }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = formatFpsLabel(fps),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) AccentPrimary else TextPrimary
                            )
                        }
                    }
                }
            }

            // Initial Video Clips Picker
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Clips", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceElevated, RoundedCornerShape(6.dp))
                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                        .clickable(enabled = !isCreating) {
                            multiVideoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("pick_initial_videos_button"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedUris.isEmpty()) "Select videos from device (optional)" else "${selectedUris.size} video(s) selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selectedUris.isEmpty()) TextSecondary else AccentSecondary
                    )
                    Text(
                        text = "Browse",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentPrimary
                    )
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clickable(enabled = !isCreating) { onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("Cancel", style = MaterialTheme.typography.titleSmall, color = TextSecondary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(AccentPrimary, RoundedCornerShape(6.dp))
                        .clickable(enabled = !isCreating) {
                            onCreate(projectName, selectedResolution, selectedFps, selectedUris)
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                        .testTag("confirm_create_project_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = BackgroundPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "Create",
                            style = MaterialTheme.typography.titleSmall,
                            color = BackgroundPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameProjectDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentName) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(10.dp))
                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Rename Project", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPrimary,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Cancel", style = MaterialTheme.typography.titleSmall, color = TextSecondary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(AccentPrimary, RoundedCornerShape(6.dp))
                        .clickable { onConfirm(text) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("Save", style = MaterialTheme.typography.titleSmall, color = BackgroundPrimary)
                }
            }
        }
    }
}

@Composable
private fun DeleteProjectDialog(
    projectName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(10.dp))
                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Delete Project", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(
                text = "Delete \"$projectName\" and its imported project media? This cannot be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Cancel", style = MaterialTheme.typography.titleSmall, color = TextSecondary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(SemanticRed, RoundedCornerShape(6.dp))
                        .clickable { onConfirm() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("Delete", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                }
            }
        }
    }
}

private fun formatFpsLabel(fps: Double): String {
    return if (fps % 1.0 == 0.0) "${fps.toInt()} fps" else "$fps fps"
}
