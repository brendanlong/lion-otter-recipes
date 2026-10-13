package com.lionotter.recipes.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lionotter.recipes.R
import com.lionotter.recipes.ui.TestTags
import com.lionotter.recipes.ui.theme.einkEdge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        modifier = modifier.einkEdge(atTop = false),
        title = {
            Text(
                text = title,
                style = LocalTextStyle.current.copy(lineHeight = 1.25.em),
                maxLines = 3,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = LocalTextStyle.current.fontSize),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .testTag(TestTags.RECIPE_DETAIL_NAME)
                    .semantics { heading() }
            )
        },
        navigationIcon = {
            if (onBackClick != null) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}
