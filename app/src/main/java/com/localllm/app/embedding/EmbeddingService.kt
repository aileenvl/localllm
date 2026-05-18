package com.localllm.app.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer

/**
 * On-device sentence-embedding service. Wraps an ONNX BGE-class model
 * (encoder export with `last_hidden_state` as output 0) plus a WordPiece
 * tokenizer to produce mean-pooled, L2-normalised float vectors.
 *
 * Why mean-pooling instead of CLS-pooling: BGE's own model card recommends
 * mean-pool with attention-mask weighting for retrieval quality. CLS-pool
 * still produces semantically meaningful vectors (the Stage 2 prep
 * experiment passed with CLS), but mean is the canonical choice.
 *
 * Thread-safety: a single coroutine [Mutex] serialises ORT calls. ONNX
 * sessions themselves are reentrant, but holding the lock keeps the
 * tokenizer + tensor allocation path single-threaded which is simpler than
 * per-call session.run synchronisation, and embedding latency (~200 ms on
 * Pixel 6 CPU) is fast enough that the lock isn't a real bottleneck for the
 * use cases this endpoint targets.
 *
 * Lifecycle: lazy session creation, explicit [close]. The service-level
 * cache in `LLMServerService` closes the entry on idle eviction.
 */
class EmbeddingService(
    private val modelPath: String,
    private val vocabPath: String,
    private val maxSeqLen: Int = 128,
) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    @Volatile private var sessionRef: OrtSession? = null
    @Volatile private var tokenizerRef: WordPieceTokenizer? = null

    private val mu = Mutex()

    private fun session(): OrtSession {
        sessionRef?.let { return it }
        synchronized(this) {
            sessionRef?.let { return it }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(1)
            }
            val s = env.createSession(File(modelPath).absolutePath, opts)
            sessionRef = s
            return s
        }
    }

    private fun tokenizer(): WordPieceTokenizer {
        tokenizerRef?.let { return it }
        synchronized(this) {
            tokenizerRef?.let { return it }
            val t = WordPieceTokenizer.fromVocabFile(vocabPath)
            tokenizerRef = t
            return t
        }
    }

    /**
     * Embed [texts] in order. Returns a list of (vector, tokensUsed) pairs.
     * `tokensUsed` is the count of non-PAD tokens fed to the encoder
     * (including [CLS]/[SEP]), suitable for OpenAI-style `usage.prompt_tokens`.
     *
     * Runs a single batched ONNX call with a [batch, maxSeqLen] tensor
     * regardless of how many texts are passed in. BGE small at 384-dim
     * hidden size on Pixel-class CPU runs ~5x faster on a batch of 8
     * than 8 serial calls, because the matmul-bound encoder amortises
     * setup cost across the batch dimension. The cost is one
     * `[batch * seq * hidden] * 4` bytes float buffer alive during the
     * call — ~1.5 MB per batch entry at seq=128 — which is bounded by
     * the prompt-cap check at the HTTP layer.
     */
    suspend fun embed(texts: List<String>): List<Pair<FloatArray, Int>> = mu.withLock {
        require(texts.isNotEmpty()) { "embed() requires at least one text" }
        session(); tokenizer()
        embedBatch(texts)
    }

    /** Lazy init off the request path. */
    suspend fun warmUp() = mu.withLock { session(); tokenizer(); Unit }

    private fun embedBatch(texts: List<String>): List<Pair<FloatArray, Int>> {
        val batch = texts.size
        val tok = tokenizer()
        val encoded = texts.map { tok.encode(it, maxSeqLen) }
        val ids = LongArray(batch * maxSeqLen)
        val attn = LongArray(batch * maxSeqLen)
        val tt = LongArray(batch * maxSeqLen) // segment ids (all zeros for single-segment)
        val tokensUsed = IntArray(batch)
        for (b in 0 until batch) {
            val base = b * maxSeqLen
            tokensUsed[b] = encoded[b].attentionMask.sum()
            for (i in 0 until maxSeqLen) {
                ids[base + i] = encoded[b].inputIds[i].toLong()
                attn[base + i] = encoded[b].attentionMask[i].toLong()
            }
        }
        val shape = longArrayOf(batch.toLong(), maxSeqLen.toLong())
        val idTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
        val attnTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attn), shape)
        val ttTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tt), shape)
        try {
            session().run(mapOf(
                "input_ids" to idTensor,
                "attention_mask" to attnTensor,
                "token_type_ids" to ttTensor,
            )).use { result ->
                @Suppress("UNCHECKED_CAST")
                val arr = result[0].value as Array<Array<FloatArray>>   // [batch, seq, hidden]
                return List(batch) { b ->
                    val pooled = meanPool(arr[b], encoded[b].attentionMask)
                    Pair(l2Normalize(pooled), tokensUsed[b])
                }
            }
        } finally {
            idTensor.close(); attnTensor.close(); ttTensor.close()
        }
    }

    override fun close() {
        val s = sessionRef ?: return
        sessionRef = null
        tokenizerRef = null
        try { s.close() } catch (_: Exception) {}
    }

    companion object {
        /**
         * Mean-pool [seq] (shape [seq_len, hidden]) over the sequence axis,
         * weighted by [mask] (1 = real token, 0 = pad). Returns [hidden]-dim.
         * If [mask] is all zeros the result is a zero vector (caller decides
         * what to do; the production path always sets mask[0] for [CLS]).
         */
        internal fun meanPool(seq: Array<FloatArray>, mask: IntArray): FloatArray {
            require(seq.isNotEmpty()) { "empty sequence" }
            val hidden = seq[0].size
            val out = FloatArray(hidden)
            var count = 0
            val limit = minOf(seq.size, mask.size)
            for (i in 0 until limit) {
                if (mask[i] == 0) continue
                val row = seq[i]
                for (h in 0 until hidden) out[h] += row[h]
                count++
            }
            if (count == 0) return out
            val divisor = count.toFloat()
            for (h in 0 until hidden) out[h] /= divisor
            return out
        }

        internal fun l2Normalize(v: FloatArray): FloatArray {
            var s2 = 0.0
            for (x in v) s2 += x * x
            val norm = Math.sqrt(s2).toFloat().coerceAtLeast(1e-12f)
            val out = FloatArray(v.size)
            for (i in v.indices) out[i] = v[i] / norm
            return out
        }
    }
}
