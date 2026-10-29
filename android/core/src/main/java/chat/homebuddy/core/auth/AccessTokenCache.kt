package chat.homebuddy.core.auth

import chat.homebuddy.core.network.RequestCredentials
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The current access token, in memory only (it lasts an hour; the refresh token on disk can always
 * get a new one). Refreshes are serialized within a session. Session changes and persistence
 * share a separate lock, so an old refresh can neither overwrite nor save over a newer session.
 */
class AccessTokenCache(private val now: () -> Long = System::currentTimeMillis) {
    private val changingSession = Mutex()
    private var session = Session()

    internal class Session(var tokens: TokenSet? = null) {
        val id = UUID.randomUUID().toString()
        val signInTokens = tokens
        val requests = mutableSetOf<Job>()
        val refreshing = Mutex()
        var refreshToken = tokens?.refreshToken
        var credentialsLoaded = tokens != null
        var saved = false
        var revision = 0L

        fun credentials() = RequestCredentials(id, tokens?.accessToken, revision)
    }

    /** Tokens from a sign-in that hasn't been saved yet, so profile setup can call the API. */
    suspend fun put(tokens: TokenSet) = changingSession.withLock {
        replaceSession(Session(tokens))
    }

    suspend fun clear(clearStored: suspend () -> Unit = {}) = changingSession.withLock {
        replaceSession(Session())
        clearStored()
    }

    /** Invalidates memory only if the stored session still owns the requested cleanup. */
    suspend fun clearIf(clearStored: suspend () -> Boolean): Boolean = changingSession.withLock {
        clearStored().also { if (it) replaceSession(Session()) }
    }

    /** Checks ownership again while invalidating memory and persisting the rejection. */
    suspend fun rejectIfCurrent(rejected: RequestCredentials, clearStored: suspend (saved: Boolean) -> Boolean): Boolean =
        changingSession.withLock {
            if (session.credentials() != rejected) return@withLock false
            clearStored(session.saved).also { if (it) replaceSession(Session()) }
        }

    /** Cancels API work owned by the old account without waiting for its network calls. */
    private fun replaceSession(next: Session) {
        val previous = session
        session = next
        previous.requests.forEach { it.cancel(CancellationException("The authentication session changed.")) }
    }

    /** Owns a group of API requests and their cache writes until the account changes. */
    suspend fun <T> withSession(accessToken: String? = null, block: suspend (SessionWork) -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        val owner = changingSession.withLock {
            if (accessToken != null && session.signInTokens?.accessToken != accessToken) {
                throw CancellationException("The sign-in was superseded.")
            }
            session.also { it.requests += job }
        }
        try {
            val work = SessionWork(owner, job)
            work.request { block(work) }
        } finally {
            withContext(NonCancellable) { changingSession.withLock { owner.requests -= job } }
        }
    }

    inner class SessionWork internal constructor(private val owner: Session, private val job: Job) {
        /** Checks both successful and failed responses before they can affect the current account. */
        suspend fun <T> request(block: suspend () -> T): T {
            commit {}
            return try {
                block().also { commit {} }
            } catch (failure: Throwable) {
                commit {}
                throw failure
            }
        }

        /** The ownership check and local write are atomic with respect to an account change. */
        suspend fun <T> commit(block: suspend () -> T): T = changingSession.withLock {
            currentCoroutineContext().ensureActive()
            job.ensureActive()
            checkCurrent(owner)
            block()
        }
    }

    /** The refresh token that goes with the current tokens; the only copy while a sign-in is being set up. */
    suspend fun refreshToken(): String? = changingSession.withLock { session.tokens?.refreshToken }

    /** Saves only the current session, with the same lock used to save rotated refresh tokens. */
    suspend fun keepSignedIn(saveRefreshToken: suspend (String, String) -> Unit, expected: TokenSet? = null) = changingSession.withLock {
        if (expected != null && session.signInTokens !== expected) throw CancellationException("The sign-in was superseded.")
        val token = session.tokens?.refreshToken ?: throw SignInFailed("The sign-in didn't include a refresh token.")
        saveRefreshToken(token, session.id)
        session.saved = true
    }

    /** A failed old sign-in must not clear a newer account's tokens or profile. */
    suspend fun forgetSignIn(expected: TokenSet, clearProfile: suspend () -> Unit) = changingSession.withLock {
        if (session.signInTokens === expected) {
            replaceSession(Session())
            clearProfile()
        }
    }

    /**
     * A fresh access token, calling [refresh] when needed. Null when credentials are absent or rejected.
     * A superseded request throws [IOException], so it cannot be mistaken for rejection of the new session.
     * Storage callbacks run under the session lock; the network call does not hold it.
     */
    suspend fun accessToken(
        forceRefresh: Boolean,
        loadRefreshToken: suspend () -> String? = { null },
        saveRefreshToken: suspend (String) -> Unit = {},
        refresh: suspend (String) -> TokenSet?,
    ): String? = credentials(forceRefresh, loadRefreshToken, saveRefreshToken, refresh = refresh)?.accessToken

    /** A request's token and ownership are read together, including after a suspended refresh. */
    suspend fun credentials(
        forceRefresh: Boolean = false,
        loadRefreshToken: suspend () -> String? = { null },
        saveRefreshToken: suspend (String) -> Unit = {},
        rejected: RequestCredentials? = null,
        refresh: suspend (String) -> TokenSet?,
    ): RequestCredentials? {
        val requesting = changingSession.withLock {
            if (rejected != null && session.id != rejected.sessionId) return null
            session
        }
        return requesting.refreshing.withLock {
            val refreshToken = changingSession.withLock {
                checkCurrent(requesting)
                val current = requesting.tokens
                val alreadyRefreshed = rejected != null && requesting.revision != rejected.revision
                if ((!forceRefresh || alreadyRefreshed) && current != null && current.isFresh()) return requesting.credentials()
                // Load once: even after rejection, a pending sign-in must not fall back to another account.
                if (!requesting.credentialsLoaded) {
                    requesting.refreshToken = loadRefreshToken()
                    requesting.saved = requesting.refreshToken != null
                    requesting.credentialsLoaded = true
                }
                requesting.refreshToken ?: run {
                    if (requesting.tokens != null) {
                        requesting.tokens = null
                        requesting.revision++
                    }
                    return requesting.credentials()
                }
            }
            val renewed = refresh(refreshToken)
            changingSession.withLock {
                checkCurrent(requesting)
                val rotated = renewed?.refreshToken
                if (requesting.saved && rotated != null && rotated != refreshToken) saveRefreshToken(rotated)
                // Cognito normally returns no refresh token, so keep the one used for this refresh.
                requesting.tokens = renewed?.copy(refreshToken = rotated ?: refreshToken)
                requesting.refreshToken = requesting.tokens?.refreshToken
                requesting.revision++
                requesting.credentials()
            }
        }
    }

    private fun checkCurrent(requesting: Session) {
        if (session !== requesting) throw IOException("The authentication session changed during the request.")
    }

    private fun TokenSet.isFresh(): Boolean = expiresAtMillis?.let { now() < it - EXPIRY_MARGIN_MILLIS } ?: true

    private companion object {
        const val EXPIRY_MARGIN_MILLIS = 60_000L
    }
}
