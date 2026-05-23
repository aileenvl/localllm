package com.localllm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localllm.app.DocumentSummaryResponse
import com.localllm.app.R
import com.localllm.app.SearchHit
import com.localllm.app.ServerState
import kotlinx.coroutines.launch

@Composable
fun DocumentsTab(
    serverStatus: ServerState.Status,
    baseUrl: String?,
    apiKey: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var documents by remember { mutableStateOf<List<DocumentSummaryResponse>>(emptyList()) }
    var searchHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var tenantId by remember { mutableStateOf<String?>(null) }
    var docId by remember { mutableStateOf("") }
    var ingestText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var loadingList by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val serverReady = serverStatus == ServerState.Status.RUNNING && baseUrl != null

    fun refresh() {
        val url = baseUrl ?: return
        scope.launch {
            loadingList = true
            statusMessage = null
            try {
                val resp = fetchDocuments(url, apiKey)
                documents = resp.data
                tenantId = resp.tenantId
            } catch (e: Exception) {
                statusMessage = e.message ?: e.javaClass.simpleName
            } finally {
                loadingList = false
            }
        }
    }

    LaunchedEffect(serverReady, baseUrl) {
        if (serverReady) refresh() else {
            documents = emptyList()
            searchHits = emptyList()
            tenantId = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.documents_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                tenantId?.let {
                    Text(
                        text = stringResource(R.string.documents_tenant, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { refresh() }, enabled = serverReady && !loadingList) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.documents_refresh))
            }
        }

        if (!serverReady) {
            Text(
                text = stringResource(R.string.documents_server_stopped),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        statusMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = docId,
            onValueChange = { docId = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.documents_id_label)) },
            placeholder = { Text(stringResource(R.string.documents_id_placeholder)) },
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            value = ingestText,
            onValueChange = { ingestText = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            label = { Text(stringResource(R.string.documents_ingest_label)) },
            placeholder = { Text(stringResource(R.string.documents_ingest_placeholder)) },
            enabled = !busy,
        )
        Button(
            onClick = {
                val url = baseUrl ?: return@Button
                val text = ingestText.trim()
                if (text.isEmpty()) {
                    statusMessage = context.getString(R.string.documents_ingest_empty)
                    return@Button
                }
                val id = docId.trim().ifEmpty { "doc-${System.currentTimeMillis()}" }
                scope.launch {
                    busy = true
                    statusMessage = null
                    try {
                        ingestDocument(url, apiKey, id, text)
                        ingestText = ""
                        docId = ""
                        refresh()
                    } catch (e: Exception) {
                        statusMessage = e.message ?: e.javaClass.simpleName
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.documents_ingest_action))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.documents_search_label)) },
                singleLine = true,
                enabled = !busy,
            )
            OutlinedButton(
                onClick = {
                    val url = baseUrl ?: return@OutlinedButton
                    val q = searchQuery.trim()
                    if (q.isEmpty()) return@OutlinedButton
                    scope.launch {
                        busy = true
                        statusMessage = null
                        try {
                            val resp = searchDocuments(url, apiKey, q)
                            searchHits = resp.data
                            tenantId = resp.tenantId
                        } catch (e: Exception) {
                            statusMessage = e.message ?: e.javaClass.simpleName
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && searchQuery.isNotBlank(),
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null)
            }
        }

        if (loadingList) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        Text(
            text = stringResource(R.string.documents_list_heading, documents.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        documents.forEach { doc ->
            DocumentRow(
                doc = doc,
                onDelete = {
                    val url = baseUrl ?: return@DocumentRow
                    scope.launch {
                        busy = true
                        statusMessage = null
                        try {
                            deleteDocument(url, apiKey, doc.documentId)
                            refresh()
                        } catch (e: Exception) {
                            statusMessage = e.message ?: e.javaClass.simpleName
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
            )
        }

        if (searchHits.isNotEmpty()) {
            Text(
                text = stringResource(R.string.documents_search_results, searchHits.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            searchHits.forEach { hit ->
                SearchHitCard(hit)
            }
        }
    }
}

@Composable
private fun DocumentRow(
    doc: DocumentSummaryResponse,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = doc.documentId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(
                        R.string.documents_row_meta,
                        doc.chunkCount,
                        doc.model.ifEmpty { DEFAULT_EMBEDDING_MODEL },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.documents_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SearchHitCard(hit: SearchHit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.documents_hit_header,
                    hit.documentId,
                    hit.chunkIndex,
                    hit.score,
                ),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = hit.text,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
