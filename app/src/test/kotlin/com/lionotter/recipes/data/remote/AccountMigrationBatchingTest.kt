package com.lionotter.recipes.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountMigrationBatchingTest {

    private fun doc(id: String, bytes: Int) = id to mapOf<String, Any?>("payload" to "x".repeat(bytes))

    @Test
    fun `small documents are limited by count`() {
        val docs = (1..250).map { doc("d$it", 10) }
        val batches = AccountMigrationService.chunkForBatches(docs)
        assertEquals(listOf(100, 100, 50), batches.map { it.size })
        assertEquals(docs, batches.flatten())
    }

    @Test
    fun `large documents are limited by estimated bytes`() {
        val docs = (1..20).map { doc("d$it", 900 * 1024) }
        val batches = AccountMigrationService.chunkForBatches(docs)
        assertTrue(batches.size > 1)
        for (batch in batches) {
            val size = batch.sumOf { AccountMigrationService.estimateSize(it.second) }
            assertTrue("batch of $size bytes", size <= AccountMigrationService.MAX_BATCH_BYTES)
        }
        assertEquals(docs, batches.flatten())
    }

    @Test
    fun `oversized document gets its own batch`() {
        val docs = listOf(doc("small", 10), doc("huge", 5 * 1024 * 1024), doc("small2", 10))
        val batches = AccountMigrationService.chunkForBatches(docs)
        assertEquals(listOf(listOf("small"), listOf("huge"), listOf("small2")), batches.map { b -> b.map { it.first } })
    }

    @Test
    fun `estimateSize counts nested structures`() {
        val nested = mapOf("a" to listOf("hello", 1L, true, null), "b" to mapOf("c" to "dé"))
        assertTrue(AccountMigrationService.estimateSize(nested) > "hello".length + "dé".length)
    }
}
