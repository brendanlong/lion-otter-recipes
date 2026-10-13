package com.lionotter.recipes.data.remote

/**
 * A background read or write that failed after its caller moved on. Repository writes apply to
 * the local cache immediately and sync later, so their failures arrive this way (through
 * [FirestoreService.failures]) rather than as exceptions from the write call.
 */
data class DataFailure(val op: DataOp, val entityId: String? = null)

enum class DataOp {
    SAVE_RECIPE,
    DELETE_RECIPE,
    UPDATE_FAVORITE,
    UPDATE_NOTES,
    UPDATE_IMAGE,
    UPDATE_RECIPE,
    SAVE_MEAL_PLAN,
    UPDATE_MEAL_PLAN,
    DELETE_MEAL_PLAN,
    /** Removing the meal plans of a deleted recipe. */
    DELETE_RECIPE_MEAL_PLANS,
    LOAD_RECIPES,
    LOAD_MEAL_PLANS,
    /** Clearing the previous account's local data on sign-in, sign-out or account deletion. */
    CLEAR_PREVIOUS_ACCOUNT
}
