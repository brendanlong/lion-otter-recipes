package com.lionotter.recipes.ui.screens.recipedetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.ui.format.formatScaleMultiplier
import kotlin.math.roundToInt
import com.lionotter.recipes.ui.theme.LocalEinkTheme
import com.lionotter.recipes.ui.theme.einkBorder

/**
 * Scales the recipe. Shows the number of servings when the recipe says how many it makes,
 * otherwise a multiplier like "1½×".
 */
@Composable
internal fun ScaleControl(
    scale: Double,
    servings: Int?,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    val knownServings = servings?.takeIf { it > 0 }
    Card(
        border = einkBorder(),
        colors = CardDefaults.cardColors(
            // The e-ink theme's secondary container is black, for selected things.
            containerColor = if (LocalEinkTheme.current) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (knownServings == null) {
                Text(
                    text = stringResource(R.string.scale_label),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            IconButton(onClick = onDecrement) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = stringResource(R.string.decrease_scale)
                )
            }
            Text(
                text = if (knownServings != null) {
                    val scaledServings = (scale * knownServings).roundToInt().coerceAtLeast(1)
                    stringResource(R.string.servings_count, scaledServings.toString())
                } else {
                    formatScaleMultiplier(scale)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            IconButton(onClick = onIncrement) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.increase_scale)
                )
            }
        }
    }
}
