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
    val filename: String
)

/**
 * Built-in model catalog. Custom URLs from Settings are merged with this list
 * at render time.
 */
val AVAILABLE_MODELS: List<ModelInfo> = listOf(
    ModelInfo(
        id = "gemma-4-e2b",
        name = "Gemma 4 E2B IT (Q4)",
        description = "Instruction tuned, 4-bit quantized. Universal .task — runs on CPU / GPU / NPU when the backend supports it. Fastest of the two.",
        url = "https://storage.googleapis.com/karatuai-models/gemma-4-E2B-it-web.task",
        filename = "gemma-4-e2b.task"
    ),
    ModelInfo(
        id = "gemma-4-e4b",
        name = "Gemma 4 E4B IT (Q4)",
        description = "Larger parameter model. More accurate, slower. Same backends as E2B.",
        url = "https://storage.googleapis.com/karatuai-models/gemma-4-E4B-it-web.task",
        filename = "gemma-4-e4b.task"
    )
)
