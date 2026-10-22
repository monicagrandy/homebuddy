package chat.homebuddy.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown when the server no longer supports this app version (GET /client-config). */
@Composable
fun UpdateRequiredScreen(onUpdate: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Time to update", style = MaterialTheme.typography.headlineMedium)
            Text(
                "This version of Home Buddy no longer works with the service. Update the app to keep using it; your documents and chats are safe.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) { Text("Update in Google Play") }
        }
    }
}
