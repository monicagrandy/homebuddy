package chat.homebuddy.core.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The current access token, in memory only (it lasts an hour; the refresh token on disk can always
 * get a new one). Refreshes are serialized so parallel requests trigger one, not several.
 */
class AccessTokenCache(private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()

    @Volatile
    private var tokens: TokenSet? = null

    /** Tokens from a sign-in that hasn't been saved yet, so profile setup can call the API. */
    fun put(tokens: TokenSet) {
        this.tokens = tokens
    }

    fun clear() {
        tokens = null
    }

    /** The refresh token that goes with the current tokens; the only copy while a sign-in is being set up. */
    fun refreshToken(): String? = tokens?.refreshToken

    /** A fresh access token, calling [refresh] when there's none or it's about to expire. Null when [refresh] is. */
    suspend fun accessToken(forceRefresh: Boolean, refresh: suspend () -> TokenSet?): String? = mutex.withLock {
        val current = tokens
        if (!forceRefresh && current != null && current.isFresh()) return@withLock current.accessToken
        val renewed = refresh()
        tokens = renewed
        renewed?.accessToken
    }

    private fun TokenSet.isFresh(): Boolean = expiresAtMillis?.let { now() < it - EXPIRY_MARGIN_MILLIS } ?: true

    private companion object {
        const val EXPIRY_MARGIN_MILLIS = 60_000L
    }
}
