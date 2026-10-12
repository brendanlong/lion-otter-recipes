package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.StartOfWeek
import kotlinx.datetime.toJavaDayOfWeek
import java.time.DayOfWeek
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealPlannerSection(
    startOfWeek: StartOfWeek,
    onStartOfWeekChange: (StartOfWeek) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]

    val localeDefaultDay = StartOfWeek.LOCALE_DEFAULT.resolve().toJavaDayOfWeek()
        .getDisplayName(TextStyle.FULL, locale)

    val options = listOf(
        StartOfWeek.LOCALE_DEFAULT to stringResource(R.string.start_of_week_locale_default, localeDefaultDay),
        StartOfWeek.MONDAY to DayOfWeek.MONDAY.getDisplayName(TextStyle.FULL, locale),
        StartOfWeek.TUESDAY to DayOfWeek.TUESDAY.getDisplayName(TextStyle.FULL, locale),
        StartOfWeek.WEDNESDAY to DayOfWeek.WEDNESDAY.getDisplayName(TextStyle.FULL, locale),
        StartOfWeek.THURSDAY to DayOfWeek.THURSDAY.getDisplayName(TextStyle.FULL, locale),
        StartOfWeek.FRIDAY to DayOfWeek.FRIDAY.getDisplayName(TextStyle.FULL, locale),
        StartOfWeek.SATURDAY to DayOfWeek.SATURDAY.getDisplayName(TextStyle.FULL, locale),
        StartOfWeek.SUNDAY to DayOfWeek.SUNDAY.getDisplayName(TextStyle.FULL, locale)
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
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
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
