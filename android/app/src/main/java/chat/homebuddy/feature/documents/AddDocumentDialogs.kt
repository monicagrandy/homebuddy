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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun AddDocumentMenu(onUpload: () -> Unit, onScan: () -> Unit, onLink: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onUpload, modifier = Modifier.fillMaxWidth()) { Text("Upload a PDF") }
                TextButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan a manual's QR code") }
                TextButton(onClick = onLink, modifier = Modifier.fillMaxWidth()) { Text("Paste a link") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirms the name, and for links the site it comes from, before adding. */
@Composable
fun NameDocumentDialog(initialName: String, source: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                source?.let { Text("From $it", style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(value = name, onValueChange = { name = it.take(200) }, singleLine = true, label = { Text("Name") })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PasteLinkDialog(onConfirm: (ManualLink.Valid) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val link = parseManualLink(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add from a link") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("https://…") },
                isError = text.isNotBlank() && link !is ManualLink.Valid,
                supportingText = {
                    when {
                        text.isBlank() -> Text("A link to the PDF works best.")
                        link is ManualLink.NotSecure -> Text("Use an https:// link.")
                        link is ManualLink.NotALink -> Text("That isn't a web link.")
                        else -> {}
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        },
        confirmButton = { TextButton(onClick = { (link as? ManualLink.Valid)?.let(onConfirm) }, enabled = link is ManualLink.Valid) { Text("Next") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDeleteDialog(document: DocumentItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${document.name}?") },
        text = { Text("Home Buddy will stop using it in answers, on every device. This can't be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
