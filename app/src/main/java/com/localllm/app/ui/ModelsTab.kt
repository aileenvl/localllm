package com.localllm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localllm.app.ModelInfo
import com.localllm.app.R

@Composable
fun ModelsTab(
    builtIn: List<ModelInfo>,
    customUrls: List<String>,
    existingModels: Set<String>,
    activeDownloads: Map<String, Long>,
    downloadProgress: Map<String, Float>,
    onDownload: (ModelInfo) -> Unit,
    onDelete: (ModelInfo) -> Unit,
    onImport: () -> Unit
) {
    val customDesc = stringResource(R.string.catalog_custom_description)
    val custom = customUrls.mapNotNull { url ->
        val fname = url.substringAfterLast('/').takeIf { it.endsWith(".litertlm") }
            ?: return@mapNotNull null
        val bare = fname.removeSuffix(".litertlm")
        ModelInfo(
            id = bare,
            name = bare,
            description = customDesc,
            url = url,
            filename = fname
        )
    }
    val all = builtIn + custom

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.models_import))
            }
        }
        items(all) { model ->
            val isDownloaded = existingModels.contains(model.filename)
            val isDownloading = activeDownloads.containsKey(model.filename)
            val progress = downloadProgress[model.filename] ?: 0f

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDownloaded) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        color = MaterialTheme.colorScheme.secondary
                    )

                    if (isDownloading) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                        Text(
                            text = stringResource(R.string.models_downloading, (progress * 100).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.End)
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (isDownloaded) {
                                Button(
                                    onClick = { onDelete(model) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) { Text(stringResource(R.string.models_delete)) }
                            } else {
                                Button(onClick = { onDownload(model) }) { Text(stringResource(R.string.models_download)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
