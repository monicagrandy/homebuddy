package chat.homebuddy.core.auth

import android.content.Intent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

sealed interface AuthState {
    data object SignedIn : AuthState

    data class SignedOut(val reason: SignOutReason?) : AuthState
}

/**
 * Sign-in and tokens, with no state of its own: being signed in is read from [AuthStore] (a stored
 * refresh token), and the short-lived access token comes from [AccessTokenCache]. Each phone has
 * its own tokens, so one account can be signed in on several devices.
 */
class AuthRepository(
    private val client: AuthClient,
    private val store: AuthStore,
    private val cache: AccessTokenCache,
) {
    val state: Flow<AuthState> = store.stored
        .map { if (it.refreshToken != null) AuthState.SignedIn else AuthState.SignedOut(it.signOutReason) }
        .distinctUntilChanged()

    suspend fun isSignedIn(): Boolean = state.first() == AuthState.SignedIn

    fun signInIntent(): Intent = client.signInIntent()

    /**
     * Exchanges the sign-in result for tokens and keeps them in memory only, so the profile can be
     * set up with them. Nothing is saved until [keepSignedIn], so a sign-in that fails half-way
     * leaves no session behind.
     */
    suspend fun exchangeCode(result: Intent?): TokenSet = client.exchange(result).also(cache::put)

    /** Saves the sign-in [exchangeCode] started, which makes this phone signed in. */
    suspend fun keepSignedIn() {
        store.saveRefreshToken(cache.refreshToken() ?: throw SignInFailed("The sign-in didn't include a refresh token."))
    }

    /** Drops the tokens of a sign-in that was never kept. */
    fun forgetUnsavedSignIn() = cache.clear()

    /** A valid access token, refreshed when needed. Null when there's no usable session. Throws IOException offline. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? = cache.accessToken(forceRefresh) {
        val refreshToken = store.stored.first().refreshToken ?: cache.refreshToken() ?: return@accessToken null
        val renewed = client.refresh(refreshToken) ?: return@accessToken null
        val rotated = renewed.refreshToken
        if (rotated != null && rotated != refreshToken && isSignedIn()) store.saveRefreshToken(rotated)
        // Cognito doesn't rotate refresh tokens by default, so a refresh usually returns none.
        renewed.copy(refreshToken = rotated ?: refreshToken)
    }

    /** Forgets this phone's session. */
    suspend fun clear(reason: SignOutReason?) {
        cache.clear()
        store.clear(reason)
    }

    suspend fun clearSignOutReason() = store.clearSignOutReason()

    /** The hosted UI's logout page. Opening it ends the browser's sign-in too, so the next sign-in can pick another account. */
    fun signOutUrl(): String = client.signOutUrl()
}
