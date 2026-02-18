package com.lionotter.recipes.ui.screens.recipelist.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.ui.components.SwipeActionBox
import com.lionotter.recipes.ui.components.SwipeActionBoxState
import com.lionotter.recipes.ui.components.rememberSwipeActionBoxState

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeableRecipeCard(
    recipe: Recipe,
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit,
    onFavoriteClick: () -> Unit,
    swipeState: SwipeActionBoxState = rememberSwipeActionBoxState()
) {
    var showMenu by remember { mutableStateOf(false) }

    SwipeActionBox(
        state = swipeState,
        onEndToStartAction = onDeleteRequest,
        enableStartToEnd = false,
        enableEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    ) {
        RecipeCard(
            recipe = recipe,
            onClick = onClick,
            onLongClick = { showMenu = true },
            showMenu = showMenu,
            onDismissMenu = { showMenu = false },
            onDeleteRequest = onDeleteRequest,
            onFavoriteClick = onFavoriteClick
        )
    }
}
