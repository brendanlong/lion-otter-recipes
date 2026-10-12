package com.lionotter.recipes.di

import com.lionotter.recipes.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val SENSITIVE_HEADERS = setOf(
        HttpHeaders.Authorization,
        HttpHeaders.Cookie,
        HttpHeaders.SetCookie,
        "x-api-key"
    )

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient {
        return HttpClient(Android) {
            install(ContentNegotiation) {
                json(json)
            }

            if (BuildConfig.DEBUG) {
                install(Logging) {
                    level = LogLevel.HEADERS
                    sanitizeHeader { header -> SENSITIVE_HEADERS.any { it.equals(header, ignoreCase = true) } }
                }
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 120_000 // 2 minutes for AI calls
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
        }
    }
}
