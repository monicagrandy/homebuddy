package chat.homebuddy.core.auth

import android.content.Intent
import chat.homebuddy.core.network.AccessTokenProvider
import chat.homebuddy.core.network.BootstrapRequest
import chat.homebuddy.core.network.HomeBuddyApi
import chat.homebuddy.core.network.HouseholdDto
import chat.homebuddy.core.network.UserDto
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * The signed-in session: restores it at launch, signs in and out, and supplies tokens to the
 * HTTP client. Each phone has its own Cognito tokens, so one account can be signed in on several
 * devices; when the server rejects this device's session (for example the account was deleted
 * elsewhere), local data is wiped and the user is sent back to sign-in.
 */
class SessionRepository(
    private val authClient: AuthClient,
    private val api: HomeBuddyApi,
    private val wipers: Set<LocalDataWiper>,
    private val scope: CoroutineScope,
) : AccessTokenProvider {
    private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun signInIntent(): Intent = authClient.signInIntent()

    suspend fun restore() {
        if (!authClient.hasSession()) {
            _state.value = SessionState.SignedOut()
            return
        }
        loadProfile { api.me() }
    }

    suspend fun completeSignIn(result: Intent?) {
        _state.value = SessionState.SigningIn
        val tokens = try {
            authClient.completeSignIn(result)
        } catch (_: SignInCancelled) {
            _state.value = SessionState.SignedOut()
            return
        } catch (_: IOException) {
            _state.value = SessionState.Failed(OFFLINE)
            return
        } catch (_: SignInFailed) {
            _state.value = SessionState.Failed(SIGN_IN_FAILED)
            return
        }
        val idToken = tokens.idToken ?: run {
            authClient.clear()
            _state.value = SessionState.Failed(SIGN_IN_FAILED)
            return
        }
        loadProfile { api.bootstrap(BootstrapRequest(idToken = idToken, accessToken = tokens.accessToken)) }
    }

    /** Signs out of this device only. Returns the hosted UI's logout page to open in the browser. */
    suspend fun signOut(): String {
        endSession(message = null)
        return authClient.signOutUrl()
    }

    fun onHouseholdCreated(household: Household) {
        val current = _state.value as? SessionState.SignedIn ?: return
        _state.value = current.copy(households = current.households + household)
    }

    override suspend fun accessToken(forceRefresh: Boolean): String? = authClient.tokens(forceRefresh)?.accessToken

    override fun onSessionRejected() {
        scope.launch { endSession(SESSION_ENDED) }
    }

    private suspend fun loadProfile(fetchUser: suspend () -> UserDto) {
        try {
            val user = fetchUser().toUser()
            val households = api.households().map { it.toHousehold() }
            _state.value = SessionState.SignedIn(user, households)
        } catch (error: HttpException) {
            when (error.code()) {
                401 -> endSession(SESSION_ENDED)
                403 -> {
                    authClient.clear()
                    _state.value = SessionState.Failed(NOT_IN_BETA)
                }
                else -> _state.value = SessionState.Failed(SOMETHING_WENT_WRONG)
            }
        } catch (_: IOException) {
            _state.value = SessionState.Failed(OFFLINE)
        }
    }

    private suspend fun endSession(message: String?) {
        authClient.clear()
        wipers.forEach { it.wipe() }
        _state.value = SessionState.SignedOut(message)
    }

    companion object {
        const val SESSION_ENDED = "You've been signed out. Please sign in again."
        const val SIGN_IN_FAILED = "Couldn't sign in. Please try again."
        const val NOT_IN_BETA = "This account isn't part of the Home Buddy beta yet."
        const val OFFLINE = "Couldn't reach Home Buddy. Check your connection and try again."
        const val SOMETHING_WENT_WRONG = "Something went wrong. Please try again."
    }
}

internal fun UserDto.toUser() = User(id = id, email = email, displayName = displayName)

fun HouseholdDto.toHousehold() =
    Household(id = id, name = name, zipCode = zipCode, homeType = homeType, role = role)
