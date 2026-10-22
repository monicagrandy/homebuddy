package chat.homebuddy.feature.account

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** The account tab. */
@Serializable
data object AccountDestination

fun NavGraphBuilder.accountDestination(onSignOut: () -> Unit) {
    composable<AccountDestination> { AccountRoute(onSignOut = onSignOut) }
}
