package chat.homebuddy.core.domain

import chat.homebuddy.core.auth.SignOutReason
import chat.homebuddy.core.data.profile.Household
import chat.homebuddy.core.data.profile.User

/** Where the app stands, derived from what's stored (see [ObserveSessionUseCase]); nothing holds it in memory. */
sealed interface SessionState {
    /** Reading the session, finishing its previous cleanup, or fetching a missing signed-in profile. */
    data object Loading : SessionState

    data class SignedOut(val reason: SignOutReason?) : SessionState

    data class SignedIn(val user: User, val households: List<Household>) : SessionState {
        val needsHome: Boolean get() = households.isEmpty()
    }
}
