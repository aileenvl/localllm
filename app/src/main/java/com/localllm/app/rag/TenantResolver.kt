package com.localllm.app.rag

/**
 * Resolve the RAG tenant for an incoming HTTP request.
 *
 * Priority: explicit [clientId] (`X-Client-Id`) → [userAgent] → `"anonymous"`.
 * Output is lowercased + trimmed so `Foo/1.0` and `foo/1.0` collapse.
 */
fun resolveTenantFromHeaders(clientId: String?, userAgent: String?): String {
    val explicit = clientId?.trim().orEmpty()
    if (explicit.isNotEmpty()) return explicit.lowercase()
    val ua = userAgent?.trim().orEmpty()
    if (ua.isNotEmpty()) return ua.lowercase()
    return "anonymous"
}
