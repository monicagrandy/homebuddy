package chat.homebuddy.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.homebuddy.feature.account.AccountRoute
import chat.homebuddy.feature.chat.ChatRoute
import chat.homebuddy.feature.documents.DocumentsRoute
import chat.homebuddy.feature.documents.IncomingShares

private enum class Tab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.Filled.Email),
    DOCUMENTS("Documents", Icons.AutoMirrored.Filled.List),
    ACCOUNT("Account", Icons.Filled.AccountCircle),
}

@Composable
fun MainScaffold(incomingShares: IncomingShares, onSignOut: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
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
            Tab.CHAT -> ChatRoute(modifier = content)
            Tab.ACCOUNT -> AccountRoute(onSignOut = onSignOut, modifier = content)
        }
    }
}
