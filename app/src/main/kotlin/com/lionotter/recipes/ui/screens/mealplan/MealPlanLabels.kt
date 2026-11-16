package com.lionotter.recipes.ui.screens.mealplan

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.MealType
import kotlinx.datetime.LocalDate

@Composable
@ReadOnlyComposable
internal fun MealType.label(): String = stringResource(
    when (this) {
        MealType.BREAKFAST -> R.string.breakfast
        MealType.LUNCH -> R.string.lunch
        MealType.DINNER -> R.string.dinner
        MealType.SNACK -> R.string.snack
    }
)

/** The heading over a day's meals. */
@Composable
internal fun DayHeader(date: LocalDate, modifier: Modifier = Modifier, isToday: Boolean = false) {
    val dayText = MealPlanDateFormat.dayHeader(date)
    Text(
        text = if (isToday) stringResource(R.string.day_header_today, dayText) else dayText,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(vertical = 4.dp)
            .semantics { heading() }
    )
}
