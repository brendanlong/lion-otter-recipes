package com.lionotter.recipes.util

import javax.inject.Qualifier

/**
 * The coroutine dispatchers, injected (provided by `AppModule`) rather than referenced
 * directly so tests can run everything on a test dispatcher. They live here rather than in
 * `di` so the domain layer can use them.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
