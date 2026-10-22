package chat.homebuddy.feature.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chat.homebuddy.app.R

@Composable
fun AddDocumentMenu(onUpload: () -> Unit, onScan: () -> Unit, onLink: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.documents_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onUpload, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.documents_add_upload)) }
                TextButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.documents_add_scan)) }
                TextButton(onClick = onLink, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.documents_add_link)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Confirms the name, and for links the site it comes from, before adding. */
@Composable
fun NameDocumentDialog(initialName: String, source: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.documents_name_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                source?.let { Text(stringResource(R.string.documents_name_source, it), style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(value = name, onValueChange = { name = it.take(200) }, singleLine = true, label = { Text(stringResource(R.string.documents_name_label)) })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.documents_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun PasteLinkDialog(onConfirm: (ManualLink.Valid) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val link = parseManualLink(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.documents_link_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.documents_link_placeholder)) },
                isError = text.isNotBlank() && link !is ManualLink.Valid,
                supportingText = {
                    when {
                        text.isBlank() -> Text(stringResource(R.string.documents_link_hint))
                        link is ManualLink.NotSecure -> Text(stringResource(R.string.documents_link_not_secure))
                        link is ManualLink.NotALink -> Text(stringResource(R.string.documents_link_not_a_link))
                        else -> {}
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        },
        confirmButton = { TextButton(onClick = { (link as? ManualLink.Valid)?.let(onConfirm) }, enabled = link is ManualLink.Valid) { Text(stringResource(R.string.documents_link_next)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ConfirmDeleteDialog(document: DocumentItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.documents_delete_title, document.name)) },
        text = { Text(stringResource(R.string.documents_delete_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
