package com.lionotter.recipes.data.sync

import android.util.Log
import com.powersync.connectors.PowerSyncBackendConnector
import com.powersync.connectors.PowerSyncCredentials
import com.powersync.PowerSyncDatabase
import com.powersync.db.crud.CrudEntry
import com.powersync.db.crud.UpdateType
import com.lionotter.recipes.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * PowerSync connector that bridges local CRUD operations to Supabase.
 *
 * Upload uses LWW (last-write-wins) upsert functions on the server side
 * that compare `updated_at` timestamps to resolve conflicts.
 */
class RecipeSupabaseConnector(
    private val supabaseClient: SupabaseClient
) : PowerSyncBackendConnector() {

    companion object {
        private const val TAG = "RecipeSupabaseConnector"
    }

    override suspend fun fetchCredentials(): PowerSyncCredentials {
        val session = supabaseClient.auth.currentSessionOrNull()
            ?: throw IllegalStateException("Not authenticated")

        // Refresh the session if needed
        supabaseClient.auth.refreshCurrentSession()
        val refreshedSession = supabaseClient.auth.currentSessionOrNull()
            ?: throw IllegalStateException("Failed to refresh session")

        return PowerSyncCredentials(
            endpoint = BuildConfig.POWERSYNC_URL,
            token = refreshedSession.accessToken
        )
    }

    override suspend fun uploadData(database: PowerSyncDatabase) {
        val transaction = database.getNextCrudTransaction() ?: return

        var lastEntry: CrudEntry? = null
        try {
            for (entry in transaction.crud) {
                lastEntry = entry
                val userId = supabaseClient.auth.currentUserOrNull()?.id
                    ?: throw IllegalStateException("Not authenticated during upload")

                when (entry.op) {
                    UpdateType.PUT -> upsertEntry(entry, userId)
                    UpdateType.PATCH -> upsertEntry(entry, userId)
                    UpdateType.DELETE -> deleteEntry(entry)
                }
            }
            transaction.complete(null)
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for entry: $lastEntry", e)
            throw e
        }
    }

    /**
     * Upserts an entry using Supabase PostgREST.
     * The server-side upsert functions handle LWW conflict resolution
     * by comparing updated_at timestamps.
     */
    private suspend fun upsertEntry(entry: CrudEntry, userId: String) {
        // Build JSON with the CRUD data plus id and owner_id
        val jsonData = buildJsonObject {
            entry.opData?.jsonValues?.forEach { (key, value) ->
                put(key, value)
            }
            put("id", JsonPrimitive(entry.id))
            put("owner_id", JsonPrimitive(userId))
        }

        val rpcFunction = when (entry.table) {
            "recipes" -> "upsert_recipe"
            "meal_plans" -> "upsert_meal_plan"
            else -> {
                Log.w(TAG, "Unknown table in upload: ${entry.table}")
                return
            }
        }

        supabaseClient.postgrest.rpc(rpcFunction, buildJsonObject {
            put("p_data", jsonData)
        })
    }

    private suspend fun deleteEntry(entry: CrudEntry) {
        supabaseClient.postgrest.from(entry.table).delete {
            filter {
                eq("id", entry.id)
            }
        }
    }
}
