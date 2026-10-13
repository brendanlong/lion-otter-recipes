package com.lionotter.recipes.ui.screens.tags

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.TagChange
import com.lionotter.recipes.domain.model.TagUsage
import com.lionotter.recipes.messages.resolve
import com.lionotter.recipes.ui.components.CollectEvents
import com.lionotter.recipes.ui.components.RecipeTopAppBar

/**
 * Lists the user's tags for starring as favorites, renaming, merging and deleting, and reviews AI
 * cleanup suggestions.
 */
@Composable
fun ManageTagsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManageTagsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    var editingTag by rememberSaveable { mutableStateOf<String?>(null) }

    val reviewing = (uiState as? ManageTagsUiState.Loaded)?.suggestions is TagSuggestions.Review
    BackHandler(enabled = reviewing, onBack = viewModel::discardSuggestions)

    CollectEvents(viewModel.events) { message ->
        snackbarHostState.showSnackbar(message.resolve(resources))
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            RecipeTopAppBar(
                title = stringResource(R.string.manage_tags),
                onBackClick = onBackClick
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        val state = uiState as? ManageTagsUiState.Loaded
        if (state == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
        when (val suggestions = state.suggestions) {
            is TagSuggestions.Review -> SuggestionReview(
                review = suggestions,
                applying = state.applying,
                onSelectedChange = viewModel::setChangeSelected,
                onApply = viewModel::applySelectedChanges,
                onDiscard = viewModel::discardSuggestions,
                modifier = contentModifier
            )
            TagSuggestions.Loading, TagSuggestions.None -> TagList(
                state = state,
                loadingSuggestions = suggestions == TagSuggestions.Loading,
                onSuggestCleanup = viewModel::suggestCleanup,
                onTagClick = { editingTag = it.name },
                onFavoriteChange = viewModel::setFavorite,
                modifier = contentModifier
            )
        }

        val tag = editingTag?.let { name -> state.tags.find { it.name == name } }
        if (tag != null && state.suggestions == TagSuggestions.None) {
            EditTagDialog(
                tag = tag,
                onRename = { newName ->
                    editingTag = null
                    viewModel.renameTag(tag.name, newName)
                },
                onDelete = {
                    editingTag = null
                    viewModel.deleteTag(tag.name)
                },
                onDismiss = { editingTag = null }
            )
        }
    }
}

@Composable
private fun TagList(
    state: ManageTagsUiState.Loaded,
    loadingSuggestions: Boolean,
    onSuggestCleanup: () -> Unit,
    onTagClick: (TagUsage) -> Unit,
    onFavoriteChange: (tag: String, favorite: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val tags = state.tags
    val busy = state.busy
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.favorite_tags_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.tag_cleanup_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (loadingSuggestions) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        Text(text = stringResource(R.string.tag_cleanup_loading))
                    }
                } else {
                    Button(
                        onClick = onSuggestCleanup,
                        enabled = !busy && tags.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.tag_cleanup_suggest))
                    }
                }
            }
            HorizontalDivider()
        }
        if (tags.isEmpty() && state.unusedFavorites.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.no_tags),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        items(tags, key = { it.name }) { tag ->
            ListItem(
                headlineContent = { Text(tag.name) },
                supportingContent = {
                    Text(pluralStringResource(R.plurals.tag_recipe_count, tag.recipeCount, tag.recipeCount))
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FavoriteTagButton(tag.name, state.isFavorite(tag.name), onFavoriteChange)
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_tag))
                    }
                },
                modifier = Modifier.clickable(enabled = !busy) { onTagClick(tag) }
            )
        }
        items(state.unusedFavorites, key = { "unused:$it" }) { tag ->
            ListItem(
                headlineContent = { Text(tag) },
                supportingContent = { Text(stringResource(R.string.favorite_tag_unused)) },
                trailingContent = { FavoriteTagButton(tag, favorite = true, onFavoriteChange) }
            )
        }
    }
}

@Composable
private fun FavoriteTagButton(
    tag: String,
    favorite: Boolean,
    onFavoriteChange: (tag: String, favorite: Boolean) -> Unit
) {
    IconToggleButton(checked = favorite, onCheckedChange = { onFavoriteChange(tag, it) }) {
        Icon(
            imageVector = if (favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
            contentDescription = if (favorite) {
                stringResource(R.string.unstar_tag, tag)
            } else {
                stringResource(R.string.star_tag, tag)
            },
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SuggestionReview(
    review: TagSuggestions.Review,
    applying: Boolean,
    onSelectedChange: (key: String, selected: Boolean) -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.tag_cleanup_review_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(review.changes, key = { it.key }) { change ->
                val selected = change.key in review.selected
                ListItem(
                    headlineContent = { Text(changeTitle(change)) },
                    supportingContent = { Text(changeDescription(change)) },
                    leadingContent = { Checkbox(checked = selected, onCheckedChange = null) },
                    modifier = Modifier.toggleable(
                        value = selected,
                        enabled = !applying,
                        role = Role.Checkbox,
                        onValueChange = { onSelectedChange(change.key, it) }
                    )
                )
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onDiscard, enabled = !applying, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.tag_cleanup_discard))
            }
            Button(
                onClick = onApply,
                enabled = !applying && review.selected.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                if (applying) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    val count = review.selected.size
                    Text(pluralStringResource(R.plurals.tag_cleanup_apply, count, count))
                }
            }
        }
    }
}

@Composable
private fun changeTitle(change: TagChange): String = when (change.to) {
    null -> stringResource(R.string.tag_change_delete_title, change.from)
    else -> stringResource(R.string.tag_change_rename_title, change.from, change.to)
}

@Composable
private fun changeDescription(change: TagChange): String {
    val recipes = pluralStringResource(R.plurals.tag_recipe_count, change.recipeCount, change.recipeCount)
    val kind = when (change.kind) {
        TagChange.Kind.RENAME -> R.string.tag_change_rename
        TagChange.Kind.MERGE -> R.string.tag_change_merge
        TagChange.Kind.DELETE -> R.string.tag_change_delete
    }
    return stringResource(R.string.tag_change_description, stringResource(kind), recipes)
}

@Composable
private fun EditTagDialog(
    tag: TagUsage,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable(tag.name, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(tag.name, selection = TextRange(tag.name.length)))
    }
    val focusRequester = remember { FocusRequester() }
    var confirmingDelete by rememberSaveable(tag.name) { mutableStateOf(false) }
    val recipes = pluralStringResource(R.plurals.tag_recipe_count, tag.recipeCount, tag.recipeCount)

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.delete_tag_title, tag.name)) },
            text = { Text(stringResource(R.string.delete_tag_message, recipes)) },
            confirmButton = {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
        return
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val newName = name.text.trim()
    val canRename = newName.isNotEmpty() && newName != tag.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_tag)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.tag_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                Text(
                    text = stringResource(R.string.edit_tag_hint, recipes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(newName) }, enabled = canRename) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmingDelete = true }) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}
