package com.localllm.app.embedding

import java.io.File

/**
 * Minimal BERT WordPiece tokenizer, uncased English. Matches the config of
 * `bge-small-en-v1.5` and other BGE / MiniLM family models that ship with a
 * BERT-style `vocab.txt`.
 *
 * Hand-rolled in Kotlin so the embedding path has no JNI tokenizer dep. If
 * we later need other tokenizer families (SentencePiece, BPE) we'll swap in
 * a canonical library; for now this covers every shipped embedding model.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val maxInputCharsPerWord: Int = 100,
    private val clsId: Int,
    private val sepId: Int,
    private val padId: Int,
    private val unkId: Int,
) {
    data class Encoded(val inputIds: IntArray, val attentionMask: IntArray)

    fun encode(text: String, maxLen: Int): Encoded {
        val words = basicTokenize(text)
        val pieces = words.flatMap { wordpiece(it) }
        // [CLS] + pieces + [SEP] ≤ maxLen.
        val take = pieces.take(maxLen - 2)
        val ids = IntArray(maxLen) { padId }
        val mask = IntArray(maxLen)
        ids[0] = clsId
        mask[0] = 1
        for ((i, p) in take.withIndex()) {
            ids[i + 1] = vocab[p] ?: unkId
            mask[i + 1] = 1
        }
        val sepIdx = take.size + 1
        ids[sepIdx] = sepId
        mask[sepIdx] = 1
        return Encoded(ids, mask)
    }

    /** Lowercase, split on whitespace, isolate punctuation into its own tokens. */
    private fun basicTokenize(text: String): List<String> {
        val cleaned = text.lowercase().trim()
        if (cleaned.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in cleaned) {
            when {
                ch.isWhitespace() -> {
                    if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                }
                isPunctuation(ch) -> {
                    if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                    out += ch.toString()
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** Approximate BERT punctuation definition. */
    private fun isPunctuation(ch: Char): Boolean {
        if (ch in '!'..'/') return true
        if (ch in ':'..'@') return true
        if (ch in '['..'`') return true
        if (ch in '{'..'~') return true
        return ch.category in PUNCT_CATEGORIES
    }

    /** Greedy longest-prefix WordPiece. */
    private fun wordpiece(word: String): List<String> {
        if (word.length > maxInputCharsPerWord) return listOf(UNK)
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: String? = null
            while (start < end) {
                val sub = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                if (sub in vocab) { found = sub; break }
                end--
            }
            if (found == null) return listOf(UNK)
            pieces += found
            start = end
        }
        return pieces
    }

    companion object {
        private const val UNK = "[UNK]"

        private val PUNCT_CATEGORIES = setOf(
            CharCategory.CONNECTOR_PUNCTUATION,
            CharCategory.DASH_PUNCTUATION,
            CharCategory.START_PUNCTUATION,
            CharCategory.END_PUNCTUATION,
            CharCategory.INITIAL_QUOTE_PUNCTUATION,
            CharCategory.FINAL_QUOTE_PUNCTUATION,
            CharCategory.OTHER_PUNCTUATION,
        )

        fun fromVocabFile(path: String): WordPieceTokenizer {
            val vocab = mutableMapOf<String, Int>()
            File(path).useLines { lines ->
                for ((i, line) in lines.withIndex()) {
                    vocab[line.trim()] = i
                }
            }
            return WordPieceTokenizer(
                vocab = vocab,
                clsId = vocab["[CLS]"] ?: error("[CLS] missing from vocab"),
                sepId = vocab["[SEP]"] ?: error("[SEP] missing from vocab"),
                padId = vocab["[PAD]"] ?: error("[PAD] missing from vocab"),
                unkId = vocab[UNK] ?: error("$UNK missing from vocab"),
            )
        }
    }
}
