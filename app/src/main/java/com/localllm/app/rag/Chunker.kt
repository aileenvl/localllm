package com.localllm.app.rag

/**
 * Paragraph-aware text chunker. Splits on blank lines first, then groups
 * paragraphs into windows of at most [maxChars] with [overlapChars] of
 * carry-over context between adjacent windows.
 *
 * Character-based rather than token-based on purpose: the tokenizer lives in
 * `EmbeddingService` and we don't want to instantiate it here just to count.
 * BGE's 128-token window is roughly 400–500 chars of English prose; the
 * default [maxChars] = 400 keeps us safely below the encoder's truncation
 * threshold so we don't silently lose tail content.
 */
object Chunker {

    private const val DEFAULT_MAX_CHARS = 400
    private const val DEFAULT_OVERLAP_CHARS = 60

    private val PARAGRAPH_SPLIT = Regex("\\n\\s*\\n")

    fun chunk(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        overlapChars: Int = DEFAULT_OVERLAP_CHARS,
    ): List<String> {
        require(maxChars > 0) { "maxChars must be > 0" }
        require(overlapChars in 0 until maxChars) { "overlapChars must be in [0, maxChars)" }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxChars) return listOf(trimmed)

        val paragraphs = PARAGRAPH_SPLIT.split(trimmed).map { it.trim() }.filter { it.isNotEmpty() }
        // If a single paragraph is itself larger than maxChars, fall back to
        // a sliding-window split on that paragraph.
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (p in paragraphs) {
            if (p.length > maxChars) {
                // Flush whatever we've accumulated first.
                if (current.isNotEmpty()) { out += current.toString().trim(); current.clear() }
                out += slidingWindow(p, maxChars, overlapChars)
                continue
            }
            val sep = if (current.isEmpty()) "" else "\n\n"
            if (current.length + sep.length + p.length > maxChars) {
                out += current.toString().trim()
                // Carry over the tail of the previous window for context.
                val tail = current.toString().takeLast(overlapChars)
                current.setLength(0)
                if (tail.isNotEmpty()) current.append(tail).append("\n\n")
                current.append(p)
            } else {
                current.append(sep).append(p)
            }
        }
        if (current.isNotEmpty()) out += current.toString().trim()
        return out
    }

    private fun slidingWindow(text: String, maxChars: Int, overlapChars: Int): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        val step = maxChars - overlapChars
        while (start < text.length) {
            val end = minOf(text.length, start + maxChars)
            out += text.substring(start, end).trim()
            if (end == text.length) break
            start += step
        }
        return out
    }
}
