package chat.homebuddy.feature.chat

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/** The chat tab: the conversation list. */
@Serializable
data object ConversationListDestination

/** One thread. A null [id] starts a new one; its id is made up on the phone and adopted by the server. */
@Serializable
data class ConversationDestination(val id: String? = null)

/** Set on the list's entry when the thread that was opened turned out to be gone. */
private const val CONVERSATION_GONE = "conversation_gone"

fun NavGraphBuilder.chatDestinations(navController: NavController) {
    composable<ConversationListDestination> { entry ->
        val conversationGone by entry.savedStateHandle.getStateFlow(CONVERSATION_GONE, false).collectAsStateWithLifecycle()
        ConversationListRoute(
            onOpen = { id -> navController.navigate(ConversationDestination(id)) },
            onNew = { navController.navigate(ConversationDestination()) },
            conversationGone = conversationGone,
            onConversationGoneShown = { entry.savedStateHandle[CONVERSATION_GONE] = false },
        )
    }
    composable<ConversationDestination> { entry ->
        val opened = entry.toRoute<ConversationDestination>().id
        ConversationRoute(
            viewModel = hiltViewModel<ConversationViewModel, ConversationViewModel.Factory> { it.create(opened) },
            // Ignores a second tap while the screen is already leaving.
            onBack = dropUnlessResumed { navController.popBackStack() },
            onGone = {
                navController.previousBackStackEntry?.savedStateHandle?.set(CONVERSATION_GONE, true)
                navController.popBackStack()
            },
        )
    }
}
