package chat.homebuddy.core.auth

data class User(val id: Int, val email: String, val displayName: String?)

data class Household(val id: Int, val name: String, val zipCode: String, val homeType: String, val role: String)

sealed interface SessionState {
    /** Checking for a saved session at app start. */
    data object Restoring : SessionState

    data class SignedOut(val message: String? = null) : SessionState

    /** Exchanging the sign-in code and setting up the profile. */
    data object SigningIn : SessionState

    data class SignedIn(val user: User, val households: List<Household>) : SessionState {
        val needsHome: Boolean get() = households.isEmpty()
    }

    data class Failed(val message: String) : SessionState
}

/** Something stored on the device for the signed-in user; wiped on sign-out and when the session is rejected. */
fun interface LocalDataWiper {
    suspend fun wipe()
}

sealed interface DeleteAccountResult {
    data object Deleted : DeleteAccountResult
    data class Failed(val message: String) : DeleteAccountResult
}
