package com.lionotter.recipes.domain.model

/** A change to a recipe's image. */
sealed interface ImageChange {
    data object Remove : ImageChange

    /** Use the local image file at [localUri] (see `ImageStore.storeImage`). */
    data class Replace(val localUri: String) : ImageChange
}
