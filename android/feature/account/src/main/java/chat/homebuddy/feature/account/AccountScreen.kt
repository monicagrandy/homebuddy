package chat.homebuddy.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

const val PRIVACY_URL = "https://homebuddy.chat/privacy"
const val TERMS_URL = "https://homebuddy.chat/terms"

data class AccountUiState(
    val name: String,
    val email: String,
    val householdName: String?,
    val retentionNote: String = "Chats are kept for 90 days after your last message.",
    val deleting: Boolean = false,
    val error: String? = null,
)

data class AccountActions(
    val onOpenLink: (String) -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

@Composable
fun AccountScreen(state: AccountUiState, actions: AccountActions, modifier: Modifier = Modifier) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Account", style = MaterialTheme.typography.headlineMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.name, style = MaterialTheme.typography.titleMedium)
                    Text(state.email, style = MaterialTheme.typography.bodyMedium)
                    state.householdName?.let { Text("Home: $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Text(state.retentionNote, style = MaterialTheme.typography.bodyMedium)
            Column {
                TextButton(onClick = { actions.onOpenLink(PRIVACY_URL) }) { Text("Privacy policy") }
                TextButton(onClick = { actions.onOpenLink(TERMS_URL) }) { Text("Terms of use") }
            }
            OutlinedButton(onClick = actions.onSignOut, enabled = !state.deleting, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out of this phone")
            }
            HorizontalDivider()
            Text("Delete account", style = MaterialTheme.typography.titleMedium)
            Text(
                "Permanently deletes your account, your home's documents and your chats, on every device. This can't be undone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.deleting) {
                CircularProgressIndicator()
            } else {
                Button(
                    onClick = { confirming = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.error != null) "Try deleting again" else "Delete account…") }
            }
        }
    }
    if (confirming) {
        ConfirmDeleteAccountDialog(
            onConfirm = {
                confirming = false
                actions.onDelete()
            },
            onDismiss = { confirming = false },
        )
    }
}

/** Deleting needs the word typed exactly, as in the web app, so it can't happen by a stray tap. */
fun canConfirmDeletion(typed: String): Boolean = typed.trim() == "DELETE"

/** The same confirmation as the web app: type DELETE. */
@Composable
fun ConfirmDeleteAccountDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete your account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your account, your home's documents and your chats will be deleted on every device.")
                OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, label = { Text("Type DELETE to confirm") })
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = canConfirmDeletion(typed)) { Text("Delete account") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
