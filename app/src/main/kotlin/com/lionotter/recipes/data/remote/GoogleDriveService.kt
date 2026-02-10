package com.lionotter.recipes.data.remote

import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents a folder in Google Drive.
 */
data class DriveFolder(
    val id: String,
    val name: String
)

/**
 * Represents a file in Google Drive.
 */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String
)

/**
 * Service for interacting with Google Drive API.
 * Handles authentication via AuthorizationClient (Google Identity Services),
 * folder operations, and file uploads/downloads.
 */
@Singleton
class GoogleDriveService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val APP_NAME = "Lion+Otter Recipes"
        private const val MIME_TYPE_FOLDER = "application/vnd.google-apps.folder"
        private const val MIME_TYPE_JSON = "application/json"
        private const val MIME_TYPE_HTML = "text/html"
        private const val MIME_TYPE_MARKDOWN = "text/markdown"

        // File names used in recipe export
        const val RECIPE_JSON_FILENAME = "recipe.json"
        const val RECIPE_HTML_FILENAME = "original.html"
        const val RECIPE_MARKDOWN_FILENAME = "recipe.md"
    }

    private var driveService: Drive? = null

    private val credentialManager: CredentialManager by lazy {
        CredentialManager.create(context)
    }

    private fun buildAuthorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_FILE)))
            .build()
    }

    /**
     * Request authorization for Google Drive access.
     * Returns the AuthorizationResult which may require user consent (hasResolution).
     */
    suspend fun authorize(): AuthorizationResult = withContext(Dispatchers.IO) {
        Identity.getAuthorizationClient(context)
            .authorize(buildAuthorizationRequest())
            .await()
    }

    /**
     * Handle authorization result and initialize the Drive service.
     * @return true if authorization was successful and Drive service is ready
     */
    suspend fun handleAuthorizationResult(result: AuthorizationResult): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val token = result.accessToken ?: return@withContext false
                initializeDriveService(token)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Parse an AuthorizationResult from a consent intent's data.
     */
    fun getAuthorizationResultFromIntent(data: Intent?): AuthorizationResult {
        return Identity.getAuthorizationClient(context)
            .getAuthorizationResultFromIntent(data)
    }

    /**
     * Check if user is signed in with Drive access.
     * Attempts silent authorization to verify access.
     */
    suspend fun isSignedIn(): Boolean {
        if (driveService != null) return true
        return try {
            val result = authorize()
            if (!result.hasResolution()) {
                handleAuthorizationResult(result)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sign out from Google Drive.
     */
    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (_: Exception) {
                // Best-effort clear
            }
            driveService = null
        }
    }

    private fun initializeDriveService(accessToken: String) {
        val credentials = GoogleCredentials.create(AccessToken(accessToken, null))
        val requestInitializer = HttpCredentialsAdapter(credentials)

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            requestInitializer
        )
            .setApplicationName(APP_NAME)
            .build()
    }

    private fun requireDriveService(): Drive {
        return driveService ?: throw IllegalStateException("Not signed in to Google Drive")
    }

    /**
     * List folders in the user's Drive that this app can access.
     */
    suspend fun listFolders(parentFolderId: String? = null): Result<List<DriveFolder>> =
        withContext(Dispatchers.IO) {
            try {
                val drive = requireDriveService()

                val parentId = parentFolderId ?: "root"
                val query = "mimeType = '$MIME_TYPE_FOLDER' and trashed = false and '$parentId' in parents"

                val result = drive.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name)")
                    .setOrderBy("name")
                    .execute()

                val folders = result.files?.map { file ->
                    DriveFolder(id = file.id, name = file.name)
                } ?: emptyList()

                Result.success(folders)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Create a new folder in Drive.
     */
    suspend fun createFolder(
        name: String,
        parentFolderId: String? = null
    ): Result<DriveFolder> = withContext(Dispatchers.IO) {
        try {
            val drive = requireDriveService()

            val fileMetadata = File().apply {
                this.name = name
                mimeType = MIME_TYPE_FOLDER
                if (parentFolderId != null) {
                    parents = listOf(parentFolderId)
                }
            }

            val folder = drive.files().create(fileMetadata)
                .setFields("id, name")
                .execute()

            Result.success(DriveFolder(id = folder.id, name = folder.name))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Upload a text file to Drive.
     */
    suspend fun uploadTextFile(
        fileName: String,
        content: String,
        mimeType: String,
        parentFolderId: String
    ): Result<DriveFile> = withContext(Dispatchers.IO) {
        try {
            val drive = requireDriveService()

            val fileMetadata = File().apply {
                name = fileName
                parents = listOf(parentFolderId)
            }

            val mediaContent = ByteArrayContent(mimeType, content.toByteArray(Charsets.UTF_8))

            val file = drive.files().create(fileMetadata, mediaContent)
                .setFields("id, name, mimeType")
                .execute()

            Result.success(DriveFile(id = file.id, name = file.name, mimeType = file.mimeType))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Upload JSON file to Drive.
     */
    suspend fun uploadJsonFile(
        fileName: String,
        content: String,
        parentFolderId: String
    ): Result<DriveFile> = uploadTextFile(fileName, content, MIME_TYPE_JSON, parentFolderId)

    /**
     * Upload HTML file to Drive.
     */
    suspend fun uploadHtmlFile(
        fileName: String,
        content: String,
        parentFolderId: String
    ): Result<DriveFile> = uploadTextFile(fileName, content, MIME_TYPE_HTML, parentFolderId)

    /**
     * Upload Markdown file to Drive.
     */
    suspend fun uploadMarkdownFile(
        fileName: String,
        content: String,
        parentFolderId: String
    ): Result<DriveFile> = uploadTextFile(fileName, content, MIME_TYPE_MARKDOWN, parentFolderId)

    /**
     * List files in a folder.
     */
    suspend fun listFiles(folderId: String): Result<List<DriveFile>> =
        withContext(Dispatchers.IO) {
            try {
                val drive = requireDriveService()

                val query = "'$folderId' in parents and trashed = false"

                val result = drive.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name, mimeType)")
                    .execute()

                val files = result.files?.map { file ->
                    DriveFile(id = file.id, name = file.name, mimeType = file.mimeType ?: "")
                } ?: emptyList()

                Result.success(files)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * List recipe folders in a parent folder.
     * Recipe folders are subfolders that contain recipe.json, original.html, and recipe.md.
     */
    suspend fun listRecipeFolders(parentFolderId: String): Result<List<DriveFolder>> =
        listFolders(parentFolderId)

    /**
     * Download a text file from Drive.
     */
    suspend fun downloadTextFile(fileId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val drive = requireDriveService()

                val outputStream = java.io.ByteArrayOutputStream()
                drive.files().get(fileId)
                    .executeMediaAndDownloadTo(outputStream)

                val content = outputStream.toString(Charsets.UTF_8.name())
                Result.success(content)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Check if a folder contains a specific file.
     */
    suspend fun findFileInFolder(folderId: String, fileName: String): DriveFile? =
        withContext(Dispatchers.IO) {
            try {
                val drive = requireDriveService()

                val query = "'$folderId' in parents and name = '$fileName' and trashed = false"

                val result = drive.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name, mimeType)")
                    .setPageSize(1)
                    .execute()

                result.files?.firstOrNull()?.let { file ->
                    DriveFile(id = file.id, name = file.name, mimeType = file.mimeType ?: "")
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Delete a file or folder from Drive.
     */
    suspend fun deleteFile(fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val drive = requireDriveService()
            drive.files().delete(fileId).execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update (overwrite) an existing text file in Drive.
     */
    suspend fun updateTextFile(
        fileId: String,
        content: String,
        mimeType: String
    ): Result<DriveFile> = withContext(Dispatchers.IO) {
        try {
            val drive = requireDriveService()
            val mediaContent = ByteArrayContent(mimeType, content.toByteArray(Charsets.UTF_8))
            val file = drive.files().update(fileId, null, mediaContent)
                .setFields("id, name, mimeType")
                .execute()
            Result.success(DriveFile(id = file.id, name = file.name, mimeType = file.mimeType))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get file metadata including modification time.
     */
    suspend fun getFileModifiedTime(fileId: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val drive = requireDriveService()
            val file = drive.files().get(fileId)
                .setFields("modifiedTime")
                .execute()
            Result.success(file.modifiedTime.value)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Find or create a folder with the given name in the parent folder.
     */
    suspend fun findOrCreateFolder(
        name: String,
        parentFolderId: String? = null
    ): Result<DriveFolder> = withContext(Dispatchers.IO) {
        try {
            val drive = requireDriveService()

            // First, try to find existing folder
            val query = buildString {
                append("mimeType = '$MIME_TYPE_FOLDER' and name = '${name.replace("'", "\\'")}' and trashed = false")
                if (parentFolderId != null) {
                    append(" and '$parentFolderId' in parents")
                }
            }

            val existingResult = drive.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .setPageSize(1)
                .execute()

            val existingFolder = existingResult.files?.firstOrNull()
            if (existingFolder != null) {
                return@withContext Result.success(
                    DriveFolder(id = existingFolder.id, name = existingFolder.name)
                )
            }

            // Create new folder if not found
            createFolder(name, parentFolderId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
