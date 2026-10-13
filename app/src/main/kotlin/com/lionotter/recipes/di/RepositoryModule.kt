package com.lionotter.recipes.di

import com.lionotter.recipes.data.repository.FirestoreMealPlanRepository
import com.lionotter.recipes.data.repository.FirestoreRecipeRepository
import com.lionotter.recipes.domain.repository.MealPlanRepository
import com.lionotter.recipes.domain.repository.RecipeRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindRecipeRepository(impl: FirestoreRecipeRepository): RecipeRepository

    @Binds
    abstract fun bindMealPlanRepository(impl: FirestoreMealPlanRepository): MealPlanRepository
}
