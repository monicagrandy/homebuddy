package chat.homebuddy.feature.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.homebuddy.app.R
import chat.homebuddy.designsystem.asString

data class DocumentsActions(
    val onRefresh: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onDelete: (DocumentItem) -> Unit = {},
    val onRetry: (UploadItem) -> Unit = {},
    val onDismiss: (UploadItem) -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(state: DocumentsUiState, actions: DocumentsActions, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item { Text(stringResource(R.string.tab_documents), style = MaterialTheme.typography.headlineMedium) }
                    state.message?.let { message ->
                        item {
                            Banner(message.asString(), onClose = actions.onMessageShown)
                        }
                    }
                    if (state.isEmpty) {
                        item { EmptyState() }
                    }
                    items(state.uploads, key = { "upload-${it.id}" }) { upload -> UploadRow(upload, actions) }
                    items(state.documents, key = { "document-${it.entryId}" }) { document -> DocumentRow(document, actions) }
                }
            }
            ExtendedFloatingActionButton(
                onClick = actions.onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.documents_add)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.documents_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.documents_empty_text),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Banner(message: String, onClose: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_ok)) }
        }
    }
}

@Composable
private fun DocumentRow(document: DocumentItem, actions: DocumentsActions) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(document.name, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(document.docType.replaceFirstChar(Char::titlecase), style = MaterialTheme.typography.bodySmall)
                    if (document.indexing) Text(stringResource(R.string.documents_indexing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            IconButton(onClick = { actions.onDelete(document) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.documents_delete_description, document.name))
            }
        }
    }
}

@Composable
private fun UploadRow(upload: UploadItem, actions: DocumentsActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(upload.name, style = MaterialTheme.typography.titleMedium)
            when (upload.status) {
                UploadStatus.WAITING -> Status(stringResource(R.string.documents_waiting), progress = true)
                UploadStatus.UPLOADING -> Status(stringResource(R.string.documents_uploading), progress = true)
                UploadStatus.READING -> Status(stringResource(R.string.documents_reading), progress = true)
                UploadStatus.FAILED, UploadStatus.DUPLICATE -> {
                    upload.message?.let {
                        Text(
                            it.asString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (upload.status == UploadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        upload.openUrl?.let { url -> AssistChip(onClick = { actions.onOpenLink(url) }, label = { Text(stringResource(R.string.documents_open_link)) }) }
                        if (upload.status == UploadStatus.FAILED && upload.openUrl == null) {
                            AssistChip(onClick = { actions.onRetry(upload) }, label = { Text(stringResource(R.string.action_try_again)) })
                        }
                        AssistChip(onClick = { actions.onDismiss(upload) }, label = { Text(stringResource(R.string.documents_dismiss)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Status(text: String, progress: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (progress) LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceVariant)
    }
}
