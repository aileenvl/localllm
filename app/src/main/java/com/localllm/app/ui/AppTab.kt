package com.localllm.app.ui

import androidx.annotation.StringRes
import com.localllm.app.R

/**
 * Top-level tabs in the app. Renamed from `Tab` to avoid colliding with
 * `androidx.compose.material3.Tab` in files that import both.
 *
 * Order matches the visual order in the TabRow and is used as the index
 * key (`ordinal`), so don't reorder casually.
 */
enum class AppTab(@StringRes val labelRes: Int) {
    MODELS(R.string.tab_catalog),
    DASHBOARD(R.string.tab_dashboard),
    CONSOLE(R.string.tab_console),
    CHAT(R.string.tab_chat),
    DOCUMENTS(R.string.tab_documents),
    SETTINGS(R.string.tab_settings)
}

/**
 * UI-side chat message. Kept separate from the server's [com.localllm.app.Message]
 * so the UI layer doesn't take a structural dependency on the API contract.
 */
data class UiMessage(
    val role: String,
    val content: String,
    val timestampMs: Long = System.currentTimeMillis()
)
