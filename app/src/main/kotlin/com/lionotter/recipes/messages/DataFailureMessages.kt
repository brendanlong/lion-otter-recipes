package com.lionotter.recipes.messages

import com.lionotter.recipes.R
import com.lionotter.recipes.data.remote.DataFailure
import com.lionotter.recipes.data.remote.DataOp

fun DataFailure.toUserMessage(): UserMessage = UserMessage(
    when (op) {
        DataOp.SAVE_RECIPE -> R.string.error_sync_save_recipe
        DataOp.DELETE_RECIPE -> R.string.delete_recipe_failed
        DataOp.UPDATE_FAVORITE -> R.string.favorite_update_failed
        DataOp.UPDATE_NOTES -> R.string.save_notes_failed
        DataOp.UPDATE_IMAGE -> R.string.error_sync_update_image
        DataOp.UPDATE_RECIPE -> R.string.error_sync_update_recipe
        DataOp.SAVE_MEAL_PLAN, DataOp.UPDATE_MEAL_PLAN -> R.string.meal_plan_save_failed
        DataOp.DELETE_MEAL_PLAN -> R.string.meal_plan_delete_failed
        DataOp.DELETE_RECIPE_MEAL_PLANS -> R.string.error_sync_delete_recipe_meal_plans
        DataOp.LOAD_RECIPES -> R.string.meal_plan_recipes_load_failed
        DataOp.LOAD_MEAL_PLANS -> R.string.meal_plan_load_failed
        DataOp.CLEAR_PREVIOUS_ACCOUNT -> R.string.error_sync_clear_previous_account
    }
)
