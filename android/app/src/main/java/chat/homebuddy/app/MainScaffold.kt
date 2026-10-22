package chat.homebuddy.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.homebuddy.core.auth.SessionState
import chat.homebuddy.feature.documents.DocumentsRoute
import chat.homebuddy.feature.documents.IncomingShares

private enum class Tab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.Filled.Email),
    DOCUMENTS("Documents", Icons.Filled.List),
    ACCOUNT("Account", Icons.Filled.AccountCircle),
}

@Composable
fun MainScaffold(session: SessionState.SignedIn, incomingShares: IncomingShares, onSignOut: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.DOCUMENTS) }
    val shared by incomingShares.pending.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        if (shared != null) tab = Tab.DOCUMENTS
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        val content = Modifier.padding(padding)
        when (tab) {
            Tab.DOCUMENTS -> DocumentsRoute(modifier = content)
            Tab.CHAT -> Placeholder("Chat is coming next.", content)
            Tab.ACCOUNT -> Column(content.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(session.user.displayName ?: session.user.email, style = MaterialTheme.typography.headlineSmall)
                Text(session.households.first().name, style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = onSignOut) { Text("Sign out") }
            }
        }
    }
}

@Composable
private fun Placeholder(text: String, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp)) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}
