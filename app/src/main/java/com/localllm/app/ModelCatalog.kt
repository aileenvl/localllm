package com.localllm.app

/**
 * Metadata for a downloadable / importable model bundle. Used both by the
 * Catalog tab (built-in entries) and to render user-added URLs as catalog rows.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val filename: String,
    /**
     * Lowercase hex SHA-256 of the file at [url], if known. The download flow
     * verifies it post-completion and deletes the file on mismatch. `null`
     * means "skip verification" — that's intentional for custom URLs where
     * we don't have a hash to compare against.
     */
    val sha256: String? = null
)

/**
 * Built-in model catalog. Custom URLs from Settings are merged with this list
 * at render time.
 */
val AVAILABLE_MODELS: List<ModelInfo> = listOf(
    ModelInfo(
        id = "gemma-4-e2b",
        name = "Gemma 4 E2B IT",
        description = "Instruction tuned, multimodal-ready Gemma 4 in LiteRT-LM format (CPU / GPU). ~2.6 GB. Fastest of the two.",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        filename = "gemma-4-e2b.litertlm",
        // SHA-256 verified locally against the actual downloaded artifact;
        // matches HF's xet-backed `x-linked-etag` header.
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    ),
    ModelInfo(
        id = "gemma-4-e4b",
        name = "Gemma 4 E4B IT",
        description = "Larger Gemma 4 — more accurate, slower. LiteRT-LM format, ~4 GB.",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        filename = "gemma-4-e4b.litertlm",
        // SHA-256 sourced from HF's `x-linked-etag` (same pattern as E2B,
        // empirically confirmed to be SHA-256 for these xet-backed files).
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
    )
)
