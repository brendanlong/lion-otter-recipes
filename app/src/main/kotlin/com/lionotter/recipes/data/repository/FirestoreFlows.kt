package com.lionotter.recipes.data.repository

import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.lionotter.recipes.data.remote.FirestoreService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

/**
 * Emits every snapshot of this query.
 *
 * Listener errors (e.g. PERMISSION_DENIED) are logged and reported through
 * [FirestoreService.reportError] instead of failing the flow, so a shared flow built
 * on top keeps its last value and never crashes the app with an unhandled exception.
 * A failed Firestore listener does not recover by itself; repositories re-create it
 * when the auth state or Firestore generation changes.
 *
 * The buffer is unlimited so no snapshot is dropped — required by [decodeIncrementally],
 * which relies on seeing every snapshot's document changes.
 */
internal fun Query.snapshots(
    firestoreService: FirestoreService,
    tag: String,
    description: String
): Flow<QuerySnapshot> = callbackFlow {
    val listener = addSnapshotListener { snapshot, error ->
        if (error != null) {
            Log.e(tag, "Error listening to $description", error)
            firestoreService.reportError("Failed to load $description: ${error.message}")
            return@addSnapshotListener
        }
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { listener.remove() }
}.buffer(Channel.UNLIMITED)

/**
 * Maps each snapshot to a list of decoded items, only decoding documents that were
 * added or modified since the previous snapshot. Documents that fail to decode are
 * logged and skipped.
 */
internal fun <T : Any> Flow<QuerySnapshot>.decodeIncrementally(
    tag: String,
    decode: (DocumentSnapshot) -> T?
): Flow<List<T>> = flow {
    val cache = HashMap<String, T?>()
    fun decodeSafely(doc: DocumentSnapshot): T? = try {
        decode(doc)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(tag, "Error deserializing document ${doc.id}", e)
        null
    }

    collect { snapshot ->
        for (change in snapshot.documentChanges) {
            val doc = change.document
            if (change.type == DocumentChange.Type.REMOVED) {
                cache.remove(doc.id)
            } else {
                cache[doc.id] = decodeSafely(doc)
            }
        }
        emit(snapshot.documents.mapNotNull { doc ->
            if (cache.containsKey(doc.id)) cache[doc.id] else decodeSafely(doc).also { cache[doc.id] = it }
        })
    }
}

/** Runs [block], logging any failure (other than cancellation) before rethrowing it. */
internal inline fun <T> logFailure(tag: String, message: String, block: () -> T): T {
    try {
        return block()
    } catch (e: Exception) {
        if (e !is CancellationException) Log.e(tag, message, e)
        throw e
    }
}
