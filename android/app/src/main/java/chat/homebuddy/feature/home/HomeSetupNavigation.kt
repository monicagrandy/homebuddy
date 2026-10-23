package chat.homebuddy.feature.home

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Setting up the first home, right after a new account's first sign-in. */
@Serializable
data object HomeSetupDestination

fun NavGraphBuilder.homeSetupDestination() {
    composable<HomeSetupDestination> { HomeSetupRoute() }
}
