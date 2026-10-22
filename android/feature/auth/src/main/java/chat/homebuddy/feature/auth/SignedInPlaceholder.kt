package chat.homebuddy.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.homebuddy.core.auth.SessionState

/** Stands in for home setup (A2) and the main screens (A3/A4) until they exist. */
@Composable
fun SignedInPlaceholder(session: SessionState.SignedIn, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Hi, ${session.user.displayName ?: session.user.email}",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                if (session.needsHome) {
                    "Next: set up your home."
                } else {
                    "Signed in to ${session.households.first().name}."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        }
    }
}
