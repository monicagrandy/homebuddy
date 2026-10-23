package chat.homebuddy.feature.auth

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Signing in. Also the first screen at launch, while the stored session is read. */
@Serializable
data object SignInDestination

fun NavGraphBuilder.signInDestination(state: () -> SignInUiState, onSignIn: () -> Unit) {
    composable<SignInDestination> { SignInScreen(state(), onSignIn = onSignIn) }
}
