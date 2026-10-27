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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Signed in when a refresh token is stored (like Shrine's session id), with the cached profile. */
class ObserveSessionUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val profile: ProfileRepository,
) {
    operator fun invoke(): Flow<SessionState> = combine(auth.state, profile.profile) { auth, profile ->
        when {
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
) {
    /** Exchanges the code, sets up the profile, and only then keeps the session, so a failure leaves nothing behind. */
    suspend operator fun invoke(page: SignInPageResult): SignInResult {
        auth.clearSignOutReason()
        val code = when (page) {
            is SignInPageResult.Code -> page
            SignInPageResult.Cancelled -> return SignInResult.Cancelled
            SignInPageResult.Failed -> return SignInResult.Failed
        }
        val tokens = try {
            auth.exchangeCode(code)
        } catch (_: IOException) {
            return SignInResult.Offline
        } catch (_: SignInFailed) {
            return SignInResult.Failed
        }
        var kept = false
        try {
            profile.bootstrap(tokens.idToken ?: return SignInResult.Failed, tokens.accessToken)
            auth.keepSignedIn()
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
                auth.forgetUnsavedSignIn()
                withContext(NonCancellable) { profile.clear() }
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
    private val ending = Mutex()

    /**
     * Runs to the end even if the caller goes away, which it will: the signed-in screens, and
     * their ViewModels, are cleared as soon as the session ends. [reason] is shown on the sign-in
     * screen until the next sign-in attempt.
     *
     * One call ends the session at a time. A call that arrives meanwhile waits for it, finds the
     * session already ended and does nothing, so the first call's reason is the one that's shown.
     */
    suspend operator fun invoke(reason: SignOutReason? = null) {
        withContext(NonCancellable) {
            ending.withLock {
                if (!auth.isSignedIn()) return@withLock
                auth.clear(reason)
                documents.wipe()
                conversations.wipe()
                profile.clear()
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
    /** False when Home Buddy couldn't be reached or failed; the cached profile is kept. */
    suspend operator fun invoke(): Boolean {
        if (!auth.isSignedIn()) return true
        return try {
            profile.refresh()
            true
        } catch (error: HttpException) {
            when (error.code()) {
                // The HTTP client has already ended the session (see NetworkSession).
                401 -> true
                403 -> {
                    endSession(SignOutReason.NOT_IN_BETA)
                    true
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
        endSession(SignOutReason.ACCOUNT_DELETED)
        return DeleteAccountResult.Deleted
    }
}
