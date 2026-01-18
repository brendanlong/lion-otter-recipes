package com.lionotter.recipes.ui.screens.recipelist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lionotter.recipes.data.remote.DriveFolder
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.ui.components.DeleteConfirmationDialog
import com.lionotter.recipes.ui.screens.googledrive.GoogleDriveUiState
import com.lionotter.recipes.ui.screens.googledrive.GoogleDriveViewModel
import com.lionotter.recipes.ui.screens.googledrive.OperationState
import com.lionotter.recipes.ui.state.RecipeListItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun RecipeListScreen(
    onRecipeClick: (String) -> Unit,
    onAddRecipeClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: RecipeListViewModel = hiltViewModel(),
    googleDriveViewModel: GoogleDriveViewModel = hiltViewModel()
) {
    val recipes by viewModel.recipes.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()
    val driveUiState by googleDriveViewModel.uiState.collectAsStateWithLifecycle()
    val operationState by googleDriveViewModel.operationState.collectAsStateWithLifecycle()
    val folders by googleDriveViewModel.folders.collectAsStateWithLifecycle()

    var showMenu by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var folderPickerMode by remember { mutableStateOf(FolderPickerMode.EXPORT) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Refresh sign-in status when screen becomes visible (e.g., after returning from Settings)
    LaunchedEffect(Unit) {
        googleDriveViewModel.refreshSignInStatus()
    }

    // Show snackbar for operation results
    LaunchedEffect(operationState) {
        when (val state = operationState) {
            is OperationState.ExportComplete -> {
                val message = if (state.failedCount > 0) {
                    "Exported ${state.exportedCount} recipes (${state.failedCount} failed)"
                } else {
                    "Exported ${state.exportedCount} recipes to Google Drive"
                }
                snackbarHostState.showSnackbar(message)
                googleDriveViewModel.resetOperationState()
            }
            is OperationState.ImportComplete -> {
                val message = buildString {
                    append("Imported ${state.importedCount} recipes")
                    if (state.skippedCount > 0 || state.failedCount > 0) {
                        append(" (")
                        val parts = mutableListOf<String>()
                        if (state.skippedCount > 0) parts.add("${state.skippedCount} skipped")
                        if (state.failedCount > 0) parts.add("${state.failedCount} failed")
                        append(parts.joinToString(", "))
                        append(")")
                    }
                }
                snackbarHostState.showSnackbar(message)
                googleDriveViewModel.resetOperationState()
            }
            is OperationState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                googleDriveViewModel.resetOperationState()
            }
            else -> {}
        }
    }

    // State for delete confirmation dialog
    var recipeToDelete by remember { mutableStateOf<Recipe?>(null) }

    // Delete confirmation dialog
    recipeToDelete?.let { recipe ->
        DeleteConfirmationDialog(
            recipeName = recipe.name,
            onConfirm = {
                viewModel.deleteRecipe(recipe.id)
                recipeToDelete = null
            },
            onDismiss = { recipeToDelete = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lion+Otter Recipes") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    // Menu for Google Drive operations
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            val isSignedIn = driveUiState is GoogleDriveUiState.SignedIn
                            val isOperating = operationState is OperationState.Exporting ||
                                    operationState is OperationState.Importing

                            DropdownMenuItem(
                                text = { Text("Export to Google Drive") },
                                onClick = {
                                    showMenu = false
                                    if (isSignedIn) {
                                        folderPickerMode = FolderPickerMode.EXPORT
                                        showFolderPicker = true
                                        googleDriveViewModel.loadFolders()
                                    } else {
                                        onSettingsClick()
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                                },
                                enabled = !isOperating
                            )
                            DropdownMenuItem(
                                text = { Text("Import from Google Drive") },
                                onClick = {
                                    showMenu = false
                                    if (isSignedIn) {
                                        folderPickerMode = FolderPickerMode.IMPORT
                                        showFolderPicker = true
                                        googleDriveViewModel.loadFolders()
                                    } else {
                                        onSettingsClick()
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                                },
                                enabled = !isOperating
                            )
                        }
                    }

                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddRecipeClick,
                containerColor = MaterialTheme.colorScheme.secondary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add recipe"
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Show progress indicator when exporting/importing
            if (operationState is OperationState.Exporting ||
                operationState is OperationState.Importing) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = if (operationState is OperationState.Exporting) {
                                "  Exporting to Google Drive..."
                            } else {
                                "  Importing from Google Drive..."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search recipes...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null
                    )
                },
                singleLine = true
            )

            // Tag filter chips
            if (availableTags.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableTags.sorted().forEach { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = { viewModel.onTagSelected(tag) },
                            label = { Text(tag) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Recipe list
            if (recipes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No recipes yet",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap + to import your first recipe",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = recipes,
                        key = { it.id }
                    ) { item ->
                        when (item) {
                            is RecipeListItem.Saved -> {
                                SwipeableRecipeCard(
                                    recipe = item.recipe,
                                    onClick = { onRecipeClick(item.id) },
                                    onDeleteRequest = { recipeToDelete = item.recipe }
                                )
                            }
                            is RecipeListItem.InProgress -> {
                                InProgressRecipeCard(
                                    name = item.name
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Folder picker dialog
    if (showFolderPicker) {
        FolderPickerDialog(
            mode = folderPickerMode,
            folders = folders,
            onFolderSelected = { folder ->
                showFolderPicker = false
                when (folderPickerMode) {
                    FolderPickerMode.EXPORT -> googleDriveViewModel.exportToGoogleDrive(folder?.id)
                    FolderPickerMode.IMPORT -> folder?.let { googleDriveViewModel.importFromGoogleDrive(it.id) }
                }
            },
            onDismiss = { showFolderPicker = false },
            onNavigateToFolder = { folder ->
                googleDriveViewModel.loadFolders(folder.id)
            }
        )
    }
}

enum class FolderPickerMode {
    EXPORT, IMPORT
}

@Composable
private fun FolderPickerDialog(
    mode: FolderPickerMode,
    folders: List<DriveFolder>,
    onFolderSelected: (DriveFolder?) -> Unit,
    onDismiss: () -> Unit,
    onNavigateToFolder: (DriveFolder) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (mode) {
                    FolderPickerMode.EXPORT -> "Export to Google Drive"
                    FolderPickerMode.IMPORT -> "Select folder to import from"
                }
            )
        },
        text = {
            Column {
                Text(
                    text = when (mode) {
                        FolderPickerMode.EXPORT -> "Select a folder to export your recipes, or export to Drive root."
                        FolderPickerMode.IMPORT -> "Select the folder containing your recipe exports."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (folders.isEmpty()) {
                    Text(
                        text = "No folders found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(200.dp)
                    ) {
                        items(folders) { folder ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        if (mode == FolderPickerMode.IMPORT) {
                                            onFolderSelected(folder)
                                        } else {
                                            onNavigateToFolder(folder)
                                        }
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = folder.name,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (mode == FolderPickerMode.EXPORT) {
                                        TextButton(onClick = { onFolderSelected(folder) }) {
                                            Text("Select")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (mode == FolderPickerMode.EXPORT) {
                TextButton(onClick = { onFolderSelected(null) }) {
                    Text("Export to Root")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableRecipeCard(
    recipe: Recipe,
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDeleteRequest()
                false // Don't dismiss - wait for confirmation
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        enableDismissFromStartToEnd = false
    ) {
        RecipeCard(
            recipe = recipe,
            onClick = onClick,
            onLongClick = { showMenu = true },
            showMenu = showMenu,
            onDismissMenu = { showMenu = false },
            onDeleteRequest = onDeleteRequest
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun RecipeCard(
    recipe: Recipe,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showMenu: Boolean,
    onDismissMenu: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                // Recipe image
                if (recipe.imageUrl != null) {
                    AsyncImage(
                        model = recipe.imageUrl,
                        contentDescription = recipe.name,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recipe.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (recipe.totalTime != null || recipe.servings != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row {
                            recipe.totalTime?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (recipe.totalTime != null && recipe.servings != null) {
                                Text(
                                    text = " • ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            recipe.servings?.let {
                                Text(
                                    text = "$it servings",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (recipe.tags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            recipe.tags.take(3).forEach { tag ->
                                TagChip(tag = tag)
                            }
                            if (recipe.tags.size > 3) {
                                Text(
                                    text = "+${recipe.tags.size - 3}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Long-press context menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = onDismissMenu
        ) {
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    onDismissMenu()
                    onDeleteRequest()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
    }
}

@Composable
private fun TagChip(tag: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun InProgressRecipeCard(name: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.6f),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Loading indicator
            CircularProgressIndicator(
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 12.dp),
                strokeWidth = 2.dp
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Importing...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
