package com.lionotter.recipes.di

import com.lionotter.recipes.util.AppJson
import com.lionotter.recipes.util.CurrentDate
import com.lionotter.recipes.util.DefaultDispatcher
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import com.lionotter.recipes.util.SystemCurrentDate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = AppJson

    // The one place the dispatchers are named; everything else injects them.
    @Suppress("InjectDispatcher")
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Suppress("InjectDispatcher")
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    fun provideCurrentDate(): CurrentDate = SystemCurrentDate()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope {
        // Background work launched here outlives any screen, so an uncaught failure is logged
        // rather than crashing the whole app.
        val handler = CoroutineExceptionHandler { _, e ->
            ErrorLog.e("ApplicationScope", "Uncaught exception in application scope", e)
        }
        return CoroutineScope(SupervisorJob() + dispatcher + handler)
    }
}
