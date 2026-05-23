package com.localllm.app.rag

import android.content.Context
import com.localllm.app.LogManager
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.query

/**
 * Thin wrapper around ObjectBox holding the document-chunk box. One instance
 * per application process — the underlying [BoxStore] is process-wide.
 *
 * Not thread-safe by itself, but ObjectBox box operations are; we serialise
 * higher-level write workflows (chunk-then-embed-then-store) in
 * `LLMServerService` because the embedding step is the bottleneck and
 * holding the mutex across it keeps the per-document operation atomic.
 */
class DocumentStore(context: Context) {

    private val store: BoxStore = MyObjectBox.builder()
        .androidContext(context.applicationContext)
        .build()
        .also { LogManager.i("DocumentStore", "ObjectBox store opened") }

    private val box: Box<DocumentChunk> = store.boxFor()

    init {
        migrateLegacyTenantIds()
    }

    /**
     * Pre-multi-tenant chunks had `tenantId = ""`. Bucket them under
     * `"anonymous"` so curl / legacy clients still see their corpus.
     */
    private fun migrateLegacyTenantIds() {
        val legacy = box.query(DocumentChunk_.tenantId.equal(""))
            .build()
            .use { it.find() }
        if (legacy.isEmpty()) return
        legacy.forEach { it.tenantId = "anonymous" }
        box.put(legacy)
        LogManager.i(
            "DocumentStore",
            "Migrated ${legacy.size} legacy chunk(s) to tenant 'anonymous'",
        )
    }

    fun count(): Long = box.count()

    /** Persist a freshly-built set of chunks for one document. */
    fun put(chunks: List<DocumentChunk>) {
        if (chunks.isEmpty()) return
        box.put(chunks)
    }

    /** All chunks belonging to [documentId] within [tenantId], ordered by [DocumentChunk.chunkIndex]. */
    fun byDocument(tenantId: String, documentId: String): List<DocumentChunk> =
        box.query(
            DocumentChunk_.documentId.equal(documentId)
                .and(DocumentChunk_.tenantId.equal(tenantId)),
        )
            .order(DocumentChunk_.chunkIndex)
            .build()
            .use { it.find() }

    /**
     * Distinct document ids for [tenantId], paired with the count of
     * chunks for each. Backs `GET /v1/documents`.
     */
    fun listDocuments(tenantId: String): List<DocumentSummary> {
        val chunks = box.query(DocumentChunk_.tenantId.equal(tenantId))
            .build()
            .use { it.find() }
        return chunks.groupBy { it.documentId }
            .map { (id, group) ->
                DocumentSummary(
                    documentId = id,
                    chunkCount = group.size,
                    embeddingModel = group.firstOrNull()?.embeddingModel ?: "",
                )
            }
            .sortedBy { it.documentId }
    }

    /** Remove every chunk for [documentId] owned by [tenantId]. Returns how many were removed. */
    fun deleteDocument(tenantId: String, documentId: String): Int {
        val ids = box.query(
            DocumentChunk_.documentId.equal(documentId)
                .and(DocumentChunk_.tenantId.equal(tenantId)),
        )
            .build()
            .use { it.findIds() }
        if (ids.isEmpty()) return 0
        box.remove(*ids)
        return ids.size
    }

    /**
     * Top-[k] nearest neighbours to [queryVec] (must be L2-normalised),
     * restricted to chunks embedded with [embeddingModel] and owned by
     * [tenantId]. Returns (chunk, distance) tuples where distance is the HNSW
     * DOT_PRODUCT distance — lower = closer for unit-norm vectors.
     *
     * ObjectBox HNSW does not compose cleanly with the tenant index, so we
     * over-fetch candidates then filter post-hoc to preserve recall.
     */
    fun nearest(
        tenantId: String,
        queryVec: FloatArray,
        k: Int,
        embeddingModel: String,
    ): List<Pair<DocumentChunk, Float>> {
        val fetchK = maxOf(k * 4, 16)
        val query = box.query(
            DocumentChunk_.embedding
                .nearestNeighbors(queryVec, fetchK)
                .and(DocumentChunk_.embeddingModel.equal(embeddingModel)),
        ).build()
        return query.use {
            it.findWithScores()
                .map { ws -> ws.get() to ws.score.toFloat() }
                .filter { (chunk, _) -> chunk.tenantId == tenantId }
                .take(k)
        }
    }

    /** Global tenant inventory for `GET /v1/tenants`. */
    fun listTenants(): List<TenantSummary> {
        val all = box.all
        return all.groupBy { it.tenantId }
            .map { (id, group) ->
                TenantSummary(
                    tenantId = id,
                    documentCount = group.map { it.documentId }.distinct().size,
                    chunkCount = group.size,
                )
            }
            .sortedBy { it.tenantId }
    }

    /** Remove every chunk for [tenantId]. Returns how many chunks were removed. */
    fun deleteTenant(tenantId: String): Int {
        val ids = box.query(DocumentChunk_.tenantId.equal(tenantId))
            .build()
            .use { it.findIds() }
        if (ids.isEmpty()) return 0
        box.remove(*ids)
        return ids.size
    }

    fun close() {
        try { store.close() } catch (e: Exception) {
            LogManager.w("DocumentStore", "Error closing ObjectBox store: ${e.message}")
        }
    }

    data class DocumentSummary(
        val documentId: String,
        val chunkCount: Int,
        val embeddingModel: String,
    )

    data class TenantSummary(
        val tenantId: String,
        val documentCount: Int,
        val chunkCount: Int,
    )
}
