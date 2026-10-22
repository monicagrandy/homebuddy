package chat.homebuddy.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import chat.homebuddy.feature.account.AccountDestination
import chat.homebuddy.feature.account.accountDestination
import chat.homebuddy.feature.chat.ConversationListDestination
import chat.homebuddy.feature.chat.chatDestinations
import chat.homebuddy.feature.documents.DocumentsDestination
import chat.homebuddy.feature.documents.documentsDestination

private enum class Tab(@StringRes val label: Int, val icon: ImageVector, val destination: Any) {
    CHAT(R.string.tab_chat, Icons.Filled.Email, ConversationListDestination),
    DOCUMENTS(R.string.tab_documents, Icons.AutoMirrored.Filled.List, DocumentsDestination()),
    ACCOUNT(R.string.tab_account, Icons.Filled.AccountCircle, AccountDestination),
}

/**
 * The signed-in app. Each tab keeps its own state when you switch away and back; a conversation
 * opens full screen over the chat tab. [share] is something shared from another app, opened in
 * the documents tab.
 */
@Composable
fun MainScaffold(share: DocumentsDestination?, onShareOpened: () -> Unit, onSignOut: () -> Unit) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val currentTab = Tab.entries.firstOrNull { entry?.destination.isIn(it) }

    Scaffold(
        bottomBar = {
            if (currentTab != null) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = { navController.navigateToTab(tab.destination, restoreState = true) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = ConversationListDestination, modifier = Modifier.padding(padding)) {
            mainDestinations(navController, onSignOut)
        }
        // After NavHost, so its graph is set by the time this runs.
        LaunchedEffect(share) {
            if (share != null) {
                // Not restoreState: that would bring back the tab's saved entry without the share.
                navController.navigateToTab(share, restoreState = false)
                onShareOpened()
            }
        }
    }
}

internal fun NavGraphBuilder.mainDestinations(navController: NavController, onSignOut: () -> Unit) {
    chatDestinations(navController)
    documentsDestination()
    accountDestination(onSignOut)
}

private fun NavDestination?.isIn(tab: Tab): Boolean = this?.hierarchy?.any { it.hasRoute(tab.destination::class) } == true

/** The usual bottom-bar navigation: one entry per tab on the back stack, and back from any tab goes to chat first. */
internal fun NavController.navigateToTab(destination: Any, restoreState: Boolean) {
    navigate(destination) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        this.restoreState = restoreState
    }
}
