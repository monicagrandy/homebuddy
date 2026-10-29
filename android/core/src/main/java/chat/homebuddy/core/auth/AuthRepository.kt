package chat.homebuddy.core.auth

import chat.homebuddy.core.network.RequestCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AuthState {
    data object SignedIn : AuthState

    data object CleaningUp : AuthState

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
    private val transitioning = Mutex()

    val state: Flow<AuthState> = store.stored
        .map {
            when {
                it.pendingCleanup != null -> AuthState.CleaningUp
                it.refreshToken != null -> AuthState.SignedIn
                else -> AuthState.SignedOut(it.signOutReason)
            }
        }
        .distinctUntilChanged()

    suspend fun isSignedIn(): Boolean = state.first() == AuthState.SignedIn

    suspend fun <T> withSessionTransition(block: suspend () -> T): T = transitioning.withLock { block() }

    suspend fun cleanupOwner(): String? = store.cleanupOwner()

    suspend fun pendingCleanup(): String? = store.stored.first().pendingCleanup

    suspend fun beginCleanup(owner: String, reason: SignOutReason?): Boolean = cache.clearIf { store.beginCleanup(owner, reason) }

    suspend fun completeCleanup(owner: String) = store.completeCleanup(owner)

    suspend fun requestCredentials(): RequestCredentials = checkNotNull(
        cache.credentials(
            loadRefreshToken = { store.stored.first().refreshToken },
            saveRefreshToken = { store.saveRefreshToken(it) },
            refresh = client::refresh,
        ),
    )

    suspend fun refreshCredentials(rejected: RequestCredentials): RequestCredentials? = cache.credentials(
        forceRefresh = true,
        loadRefreshToken = { store.stored.first().refreshToken },
        saveRefreshToken = { store.saveRefreshToken(it) },
        rejected = rejected,
        refresh = client::refresh,
    )

    /** Called under the transition lock; rejection of an older token cannot end the current session. */
    suspend fun rejectCredentials(rejected: RequestCredentials, clearUnsavedProfile: suspend () -> Unit): String? {
        var owner: String? = null
        cache.rejectIfCurrent(rejected) { saved ->
            if (saved) {
                val storedOwner = store.cleanupOwner() ?: return@rejectIfCurrent false
                if (!store.beginCleanup(storedOwner, SignOutReason.SESSION_ENDED)) return@rejectIfCurrent false
                owner = storedOwner
            } else clearUnsavedProfile()
            true
        }
        return owner
    }

    /**
     * Exchanges the sign-in page's code for tokens and keeps them in memory only, so the profile can be
     * set up with them. Nothing is saved until [keepSignedIn], so a sign-in that fails half-way
     * leaves no session behind.
     */
    suspend fun exchangeCode(code: SignInPageResult.Code): TokenSet = client.exchange(code).also { cache.put(it) }

    /** Saves the sign-in [exchangeCode] started, which makes this phone signed in. */
    suspend fun keepSignedIn(expected: TokenSet? = null) = cache.keepSignedIn(store::saveRefreshToken, expected)

    /** Drops the tokens of a sign-in that was never kept. */
    suspend fun forgetUnsavedSignIn() = cache.clear()

    /** Cleans up only the sign-in that failed, including its cached profile. */
    suspend fun forgetUnsavedSignIn(expected: TokenSet, clearProfile: suspend () -> Unit) =
        cache.forgetSignIn(expected, clearProfile)

    /** A valid access token. Null when unusable; throws IOException offline or if the session changes during refresh. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? = cache.accessToken(
        forceRefresh = forceRefresh,
        loadRefreshToken = { store.stored.first().refreshToken },
        saveRefreshToken = { store.saveRefreshToken(it) },
        refresh = client::refresh,
    )

    /** Forgets this phone's session. */
    suspend fun clear(reason: SignOutReason?, clearProfile: suspend () -> Unit = {}) = cache.clear {
        clearProfile()
        store.clear(reason)
    }

    suspend fun clearSignOutReason() = store.clearSignOutReason()

    /** The hosted UI's logout page. Opening it ends the browser's sign-in too, so the next sign-in can pick another account. */
    fun signOutUrl(): String = client.signOutUrl()
}
