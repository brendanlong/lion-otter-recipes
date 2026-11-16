package com.lionotter.recipes.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

private const val BACKOFF_SECONDS = 30L

private val NETWORK_CONSTRAINTS = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

/** Retry with exponential backoff, starting at 30 seconds. */
internal fun <B : WorkRequest.Builder<B, *>> B.withRetryBackoff(): B =
    setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)

/** Run only while connected to a network, retrying as [withRetryBackoff]. */
internal fun <B : WorkRequest.Builder<B, *>> B.withNetworkAndBackoff(): B =
    setConstraints(NETWORK_CONSTRAINTS).withRetryBackoff()
