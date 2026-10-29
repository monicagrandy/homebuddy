package chat.homebuddy.core.domain

import chat.homebuddy.core.auth.AuthRepository
import chat.homebuddy.core.auth.AuthState
import chat.homebuddy.core.auth.SignInFailed
import chat.homebuddy.core.auth.SignInPageResult
import chat.homebuddy.core.auth.SignOutReason
import chat.homebuddy.core.data.conversations.ConversationsRepository
import chat.homebuddy.core.data.documents.DocumentsRepository
import chat.homebuddy.core.data.profile.Household
import chat.homebuddy.core.data.profile.ProfileRepository
import chat.homebuddy.core.network.toApiError
import chat.homebuddy.core.network.RequestCredentials
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Signed in when a refresh token is stored (like Shrine's session id), with the cached profile. */
class ObserveSessionUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val profile: ProfileRepository,
) {
    operator fun invoke(): Flow<SessionState> = combine(auth.state, profile.profile) { auth, profile ->
        when {
            auth is AuthState.CleaningUp -> SessionState.Loading
            auth is AuthState.SignedOut -> SessionState.SignedOut(auth.reason)
            profile == null -> SessionState.Loading
            else -> SessionState.SignedIn(profile.user, profile.households)
        }
    }.distinctUntilChanged()
}

/** The home the app works with. One per account for now; a picker can choose among several later. */
class ObserveCurrentHouseholdUseCase @Inject constructor(private val profile: ProfileRepository) {
    operator fun invoke(): Flow<Household?> = profile.profile.map { it?.households?.firstOrNull() }.distinctUntilChanged()
}

sealed interface SignInResult {
    data object SignedIn : SignInResult
    data object Cancelled : SignInResult
    data object NotInBeta : SignInResult
    data object Offline : SignInResult
    data object Failed : SignInResult
}

class SignInUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val profile: ProfileRepository,
    private val endSession: EndSessionUseCase,
) {
    /** Exchanges the code, sets up the profile, and only then keeps the session, so a failure leaves nothing behind. */
    suspend operator fun invoke(page: SignInPageResult): SignInResult {
        val tokens = try {
            endSession.beforeSignIn {
                auth.clearSignOutReason()
                if (page is SignInPageResult.Code) {
                    if (auth.isSignedIn()) throw SignInFailed("A session is already signed in.")
                    auth.exchangeCode(page)
                } else null
            }
        } catch (_: IOException) {
            return SignInResult.Offline
        } catch (_: SignInFailed) {
            return SignInResult.Failed
        } ?: return if (page == SignInPageResult.Cancelled) SignInResult.Cancelled else SignInResult.Failed
        var kept = false
        try {
            profile.bootstrap(tokens.idToken ?: return SignInResult.Failed, tokens.accessToken)
            auth.withSessionTransition { auth.keepSignedIn(tokens) }
            kept = true
            return SignInResult.SignedIn
        } catch (error: HttpException) {
            return if (error.code() == 403) SignInResult.NotInBeta else SignInResult.Failed
        } catch (_: IOException) {
            return SignInResult.Offline
        } catch (_: SignInFailed) {
            return SignInResult.Failed
        } finally {
            if (!kept) {
                withContext(NonCancellable) {
                    auth.withSessionTransition { auth.forgetUnsavedSignIn(tokens) { profile.clear() } }
                }
            }
        }
    }
}

/**
 * Ends this phone's session (other devices stay signed in) and wipes what was cached for the
 * account. Every way a session ends comes through here: signing out, deleting the account, the
 * server rejecting the session, and leaving the beta.
 *
 * A singleton, so that every caller shares one lock: the session can be reported over several
 * times at once (Sign out tapped twice, or several requests rejected together on OkHttp's threads).
 */
@Singleton
class EndSessionUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val profile: ProfileRepository,
    private val documents: DocumentsRepository,
    private val conversations: ConversationsRepository,
) {
    /**
     * Captures this sign-in's identity before waiting for the transition lock. The durable cleanup
     * marker outlives its credentials; a retry or next launch finishes it before another sign-in.
     * The first reason is retained, and a delayed request cannot clear a newer sign-in, even to
     * the same account. Caller cancellation doesn't interrupt cleanup once it has started.
     */
    suspend operator fun invoke(reason: SignOutReason? = null) {
        withContext(NonCancellable) {
            val owner = auth.cleanupOwner() ?: return@withContext
            end(owner, reason)
        }
    }

    internal suspend fun end(owner: String, reason: SignOutReason?) = withContext(NonCancellable) {
        auth.withSessionTransition {
            if (auth.beginCleanup(owner, reason)) finishCleanup(owner)
        }
    }

    /** Rechecks both the request's session and token after acquiring the transition lock. */
    suspend fun reject(rejected: RequestCredentials) = withContext(NonCancellable) {
        auth.withSessionTransition {
            val owner = auth.rejectCredentials(rejected) { profile.clear() }
            if (owner != null) finishCleanup(owner)
        }
    }

    /** At startup, finish old session cleanup before restoring this session's background uploads. */
    suspend fun reconcile() = withContext(NonCancellable) {
        auth.withSessionTransition {
            resumeCleanup()
            if (auth.isSignedIn()) documents.reconcileUploads()
        }
    }

    /** No new credentials or profile writes until global data deletion has finished. */
    suspend fun <T> beforeSignIn(block: suspend () -> T): T = auth.withSessionTransition {
        withContext(NonCancellable) { resumeCleanup() }
        block()
    }

    private suspend fun resumeCleanup() {
        val owner = auth.pendingCleanup() ?: return
        if (auth.beginCleanup(owner, reason = null)) finishCleanup(owner)
    }

    private suspend fun finishCleanup(owner: String) {
        try {
            profile.clear()
            documents.wipe()
            conversations.wipe()
            auth.completeCleanup(owner)
        } catch (failure: Exception) {
            when (failure) {
                is CancellationException, is IOException -> throw failure
                else -> throw IOException("Couldn't finish removing the previous session's data.", failure)
            }
        }
    }
}

/** Signing out because the user asked to. */
class SignOutUseCase @Inject constructor(
    private val endSession: EndSessionUseCase,
    private val auth: AuthRepository,
) {
    /**
     * Ends the session, then returns the hosted UI's logout page for the caller to open. That
     * ends the browser's sign-in too, so the next sign-in can pick another account. Sessions that
     * end without the user asking skip it, so a browser never opens unprompted.
     */
    suspend operator fun invoke(): String {
        endSession()
        return auth.signOutUrl()
    }
}

/** Fetches the profile again (at launch), so changes from other devices show up. */
class RefreshProfileUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val profile: ProfileRepository,
    private val endSession: EndSessionUseCase,
) {
    suspend fun hasPendingCleanup(): Boolean = auth.pendingCleanup() != null

    /** False when pending cleanup or fetching the current profile needs a retry. */
    suspend operator fun invoke(): Boolean {
        return try {
            endSession.reconcile()
            if (!auth.isSignedIn()) return true
            profile.refresh()
            true
        } catch (error: HttpException) {
            when (error.code()) {
                // The HTTP client has already ended the session (see NetworkSession).
                401 -> true
                403 -> {
                    try {
                        endSession(SignOutReason.NOT_IN_BETA)
                        true
                    } catch (_: IOException) {
                        false
                    }
                }
                else -> false
            }
        } catch (_: IOException) {
            false
        }
    }
}

sealed interface DeleteAccountResult {
    data object Deleted : DeleteAccountResult
    data object Offline : DeleteAccountResult
    data class Failed(val message: String) : DeleteAccountResult
}

/**
 * Deletes the account everywhere (Google Play requires this in the app). Afterwards this phone is
 * signed out and wiped; other devices are signed out on their next request.
 */
class DeleteAccountUseCase @Inject constructor(
    private val profile: ProfileRepository,
    private val endSession: EndSessionUseCase,
) {
    suspend operator fun invoke(): DeleteAccountResult {
        try {
            profile.deleteAccount()
        } catch (_: IOException) {
            return DeleteAccountResult.Offline
        } catch (error: HttpException) {
            return DeleteAccountResult.Failed(error.toApiError().message)
        }
        // The account is gone on the server; this phone still has to end its session like any other.
        try {
            endSession(SignOutReason.ACCOUNT_DELETED)
        } catch (_: IOException) {
            return DeleteAccountResult.Failed("Your account was deleted, but signing out needs to be retried.")
        }
        return DeleteAccountResult.Deleted
    }
}
