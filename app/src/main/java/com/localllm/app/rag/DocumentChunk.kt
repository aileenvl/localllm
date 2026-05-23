package com.localllm.app.rag

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.VectorDistanceType

/**
 * One chunk of a document, persisted in the ObjectBox vector store.
 *
 * - `id` is auto-assigned by ObjectBox.
 * - `documentId` groups chunks back into the original document (the user-
 *   supplied id from `POST /v1/documents`). Indexed so DELETE /v1/documents/{id}
 *   can find and remove all chunks for that document in one query.
 * - `chunkIndex` is the 0-based position within the parent document so a
 *   client can stitch retrieved snippets back into context order.
 * - `embedding` is the L2-normalised float vector. The HNSW index uses
 *   `DOT_PRODUCT` distance since cosine on unit-norm vectors is just dot
 *   product — slightly faster than `COSINE` because no per-query
 *   normalisation is needed.
 * - `embeddingModel` records WHICH model produced the vector. Searches must
 *   pass the same model id; mixing dimensions across embedding models would
 *   fail at the ObjectBox layer anyway, but rejecting at the API gateway
 *   gives a cleaner error.
 */
@Entity
data class DocumentChunk(
    @Id var id: Long = 0,
    /** Per-client RAG namespace — see issue #4 multi-tenant isolation. */
    @Index var tenantId: String = "",
    @Index var documentId: String = "",
    var chunkIndex: Int = 0,
    var text: String = "",
    var metadata: String? = null,
    var embeddingModel: String = "",
    @HnswIndex(dimensions = 384, distanceType = VectorDistanceType.DOT_PRODUCT)
    var embedding: FloatArray = FloatArray(0),
) {
    /**
     * Auto-generated `equals`/`hashCode` on data classes with arrays compare by
     * reference, which is wrong for tests. Override with content-aware impls.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DocumentChunk) return false
        return id == other.id &&
            tenantId == other.tenantId &&
            documentId == other.documentId &&
            chunkIndex == other.chunkIndex &&
            text == other.text &&
            metadata == other.metadata &&
            embeddingModel == other.embeddingModel &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + tenantId.hashCode()
        h = 31 * h + documentId.hashCode()
        h = 31 * h + chunkIndex
        h = 31 * h + text.hashCode()
        h = 31 * h + (metadata?.hashCode() ?: 0)
        h = 31 * h + embeddingModel.hashCode()
        h = 31 * h + embedding.contentHashCode()
        return h
    }
}
