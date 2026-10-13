package com.lionotter.recipes.data.repository

import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.android.gms.tasks.Task
import com.lionotter.recipes.data.remote.AccountLeftException
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.DataFailure
import com.lionotter.recipes.data.remote.DataOp
import com.lionotter.recipes.data.remote.FirestoreCache
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.uid
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Emits every snapshot of this query.
 *
 * Listener errors (e.g. PERMISSION_DENIED) are logged and reported as [loadOp] through
 * [FirestoreService.reportFailure] instead of failing the flow, so a shared flow built
 * on top keeps its last value and never crashes the app with an unhandled exception.
 * A failed Firestore listener does not recover by itself; repositories re-create it
 * when the auth state or Firestore generation changes.
 *
 * The buffer is unlimited so no snapshot is dropped — required by [decodeIncrementally],
 * which relies on seeing every snapshot's document changes.
 */
internal fun Query.snapshots(
    firestoreService: FirestoreService,
    loadOp: DataOp
): Flow<QuerySnapshot> = callbackFlow {
    val listener = addSnapshotListener { snapshot, error ->
        if (error != null) {
            firestoreService.reportFailure(DataFailure(loadOp), error)
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ErrorLog.e(tag, "Error deserializing document ${doc.id}", e)
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

/**
 * Runs [block], logging any failure before rethrowing it. Cancellation isn't logged, and an
 * [AccountLeftException] (expected while signing out) only as a warning.
 */
internal inline fun <T> logFailure(tag: String, message: String, block: () -> T): T {
    try {
        return block()
    } catch (e: Exception) {
        when (e) {
            is CancellationException -> Unit
            is AccountLeftException -> ErrorLog.w(tag, message, e)
            else -> ErrorLog.e(tag, message, e)
        }
        throw e
    }
}

/**
 * Runs a fire-and-forget write for the current user. During a guest's sign-in the write waits in
 * [scope] for it to finish and then goes to the resulting account, so e.g. a worker saving a
 * recipe mid-transition doesn't lose it. During sign-out or account deletion the write is dropped
 * (and logged): it was meant for the account leaving the device, and must not land in the next
 * one. A failure to start the write is reported as [failure].
 */
internal fun AuthService.writeForCurrentUser(
    scope: CoroutineScope,
    firestoreCache: FirestoreCache,
    failure: DataFailure,
    write: (uid: String) -> Unit
) {
    val epoch = leaveEpoch
    val uid = authState.value.uid
    if (uid != null) {
        try {
            write(uid)
        } catch (e: Exception) {
            firestoreCache.reportFailure(failure, e)
        }
        return
    }
    if (epoch % 2 != 0) {
        ErrorLog.w(WRITES_TAG, "Dropped ${failure.op}: the account is being signed out")
        return
    }
    scope.launch {
        try {
            write(awaitUid(epoch))
        } catch (e: AccountLeftException) {
            ErrorLog.w(WRITES_TAG, "Dropped ${failure.op}: the account was signed out", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            firestoreCache.reportFailure(failure, e)
        }
    }
}

private const val WRITES_TAG = "FirestoreWrites"

/** Reports [failure] if this task fails. */
internal fun <T> Task<T>.reportFailureTo(firestoreService: FirestoreService, failure: DataFailure): Task<T> =
    addOnFailureListener { e -> firestoreService.reportFailure(failure, e) }
