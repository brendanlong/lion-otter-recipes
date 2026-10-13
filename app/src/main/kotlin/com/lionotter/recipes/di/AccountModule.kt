package com.lionotter.recipes.di

import android.content.Context
import com.lionotter.recipes.R
import com.lionotter.recipes.data.account.AccountBoundWork
import com.lionotter.recipes.data.image.LocalImageUploader
import com.lionotter.recipes.data.image.PendingImageUploads
import com.lionotter.recipes.data.remote.AccountDeletionService
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthSession
import com.lionotter.recipes.data.remote.FirestoreCache
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.RemoteAccountDeletion
import com.lionotter.recipes.data.remote.WebClientId
import com.lionotter.recipes.worker.RunningAccountWork
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** What `AccountLifecycle` composes, and the Google sign-in configuration. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AccountModule {
    @Binds
    abstract fun bindAuthSession(impl: AuthService): AuthSession

    @Binds
    abstract fun bindFirestoreCache(impl: FirestoreService): FirestoreCache

    @Binds
    abstract fun bindRemoteAccountDeletion(impl: AccountDeletionService): RemoteAccountDeletion

    @Binds
    abstract fun bindPendingImageUploads(impl: LocalImageUploader): PendingImageUploads

    @Binds
    abstract fun bindAccountBoundWork(impl: RunningAccountWork): AccountBoundWork

    companion object {
        @Provides
        @WebClientId
        fun provideWebClientId(@ApplicationContext context: Context): String =
            context.getString(R.string.default_web_client_id)
    }
}
