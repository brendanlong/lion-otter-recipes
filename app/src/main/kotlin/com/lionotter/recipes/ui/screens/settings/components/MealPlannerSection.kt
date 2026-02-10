package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.StartOfWeek
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealPlannerSection(
    startOfWeek: StartOfWeek,
    onStartOfWeekChange: (StartOfWeek) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val localeDefaultDay = DayOfWeek.of(StartOfWeek.LOCALE_DEFAULT.resolve().value)
        .getDisplayName(TextStyle.FULL, Locale.getDefault())

    val options = listOf(
        StartOfWeek.LOCALE_DEFAULT to stringResource(R.string.start_of_week_locale_default, localeDefaultDay),
        StartOfWeek.MONDAY to DayOfWeek.MONDAY.getDisplayName(TextStyle.FULL, Locale.getDefault()),
        StartOfWeek.TUESDAY to DayOfWeek.TUESDAY.getDisplayName(TextStyle.FULL, Locale.getDefault()),
        StartOfWeek.WEDNESDAY to DayOfWeek.WEDNESDAY.getDisplayName(TextStyle.FULL, Locale.getDefault()),
        StartOfWeek.THURSDAY to DayOfWeek.THURSDAY.getDisplayName(TextStyle.FULL, Locale.getDefault()),
        StartOfWeek.FRIDAY to DayOfWeek.FRIDAY.getDisplayName(TextStyle.FULL, Locale.getDefault()),
        StartOfWeek.SATURDAY to DayOfWeek.SATURDAY.getDisplayName(TextStyle.FULL, Locale.getDefault()),
        StartOfWeek.SUNDAY to DayOfWeek.SUNDAY.getDisplayName(TextStyle.FULL, Locale.getDefault())
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.meal_planner_settings),
            style = MaterialTheme.typography.titleMedium
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.start_of_week),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.start_of_week_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = options.find { it.first == startOfWeek }?.second ?: startOfWeek.name,
                    onValueChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { (value, displayName) ->
                        DropdownMenuItem(
                            text = { Text(displayName) },
                            onClick = {
                                onStartOfWeekChange(value)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}
