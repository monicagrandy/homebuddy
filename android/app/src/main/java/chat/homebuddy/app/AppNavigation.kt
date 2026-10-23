package chat.homebuddy.app

import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import chat.homebuddy.feature.auth.AppUiState
import chat.homebuddy.feature.auth.SignInDestination
import chat.homebuddy.feature.auth.SignInUiState
import chat.homebuddy.feature.auth.signInDestination
import chat.homebuddy.feature.documents.DocumentsDestination
import chat.homebuddy.feature.home.HomeSetupDestination
import chat.homebuddy.feature.home.homeSetupDestination
import kotlinx.serialization.Serializable

/** The signed-in app: the tabs, which have their own NavHost (see MainScaffold). */
@Serializable
data object MainDestination

/**
 * The root graph. Which destination you're on follows the session (see [navigateForSession]).
 * ViewModels belong to the destination they're created on, so leaving the signed-in app clears
 * all of its ViewModels, the tabs' included.
 */
internal fun NavGraphBuilder.appDestinations(
    signInState: () -> SignInUiState,
    onSignIn: () -> Unit,
    pendingShare: () -> DocumentsDestination?,
    onShareOpened: () -> Unit,
    onSignOut: () -> Unit,
) {
    signInDestination(signInState, onSignIn)
    homeSetupDestination()
    composable<MainDestination> { MainScaffold(share = pendingShare(), onShareOpened = onShareOpened, onSignOut = onSignOut) }
}

/** Where the session puts you. Null while it's loading, so a screen restored after process death stays put. */
internal fun AppUiState.destination(): Any? = when (this) {
    is AppUiState.Loading -> null
    is AppUiState.SignedOut -> SignInDestination
    AppUiState.HomeSetup -> HomeSetupDestination
    AppUiState.Main -> MainDestination
}

/**
 * Goes to [destination] and clears the back stack, so nothing is ever behind it: back from the
 * signed-in app leaves the app instead of returning to sign-in, and back from sign-in never goes
 * into the app.
 */
internal fun NavController.navigateForSession(destination: Any) {
    if (currentDestination?.hasRoute(destination::class) == true) return
    navigate(destination) {
        popUpTo(graph.id) { inclusive = true }
    }
}
