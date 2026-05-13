package com.localllm.app.rag

import android.content.Context
import com.localllm.app.LogManager
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.query
import io.objectbox.query.QueryBuilder

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

    fun count(): Long = box.count()

    /** Persist a freshly-built set of chunks for one document. */
    fun put(chunks: List<DocumentChunk>) {
        if (chunks.isEmpty()) return
        box.put(chunks)
    }

    /** All chunks belonging to [documentId], ordered by [DocumentChunk.chunkIndex]. */
    fun byDocument(documentId: String): List<DocumentChunk> =
        box.query(DocumentChunk_.documentId.equal(documentId))
            .order(DocumentChunk_.chunkIndex)
            .build()
            .use { it.find() }

    /**
     * Distinct document ids present in the store, paired with the count of
     * chunks for each. Backs `GET /v1/documents`.
     */
    fun listDocuments(): List<DocumentSummary> {
        val all = box.all
        return all.groupBy { it.documentId }
            .map { (id, group) ->
                DocumentSummary(
                    documentId = id,
                    chunkCount = group.size,
                    embeddingModel = group.firstOrNull()?.embeddingModel ?: "",
                )
            }
            .sortedBy { it.documentId }
    }

    /** Remove every chunk associated with [documentId]. Returns how many were removed. */
    fun deleteDocument(documentId: String): Int {
        val ids = box.query(DocumentChunk_.documentId.equal(documentId))
            .build()
            .use { it.findIds() }
        if (ids.isEmpty()) return 0
        box.remove(*ids)
        return ids.size
    }

    /**
     * Top-[k] nearest neighbours to [queryVec] (must be L2-normalised),
     * restricted to chunks embedded with [embeddingModel]. Returns
     * (chunk, distance) tuples where distance is the HNSW DOT_PRODUCT
     * distance — lower = closer for unit-norm vectors.
     */
    fun nearest(queryVec: FloatArray, k: Int, embeddingModel: String): List<Pair<DocumentChunk, Float>> {
        val query = box.query(
            DocumentChunk_.embedding
                .nearestNeighbors(queryVec, k)
                .and(DocumentChunk_.embeddingModel.equal(embeddingModel))
        ).build()
        return query.use {
            it.findWithScores().map { ws -> ws.get() to ws.score.toFloat() }
        }
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
}
