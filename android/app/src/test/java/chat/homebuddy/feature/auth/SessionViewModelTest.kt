package chat.homebuddy.feature.auth

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import chat.homebuddy.app.MainDispatcherRule
import chat.homebuddy.app.R
import chat.homebuddy.core.auth.SignInPageResult
import chat.homebuddy.core.auth.SignOutReason
import chat.homebuddy.core.auth.TokenSet
import chat.homebuddy.core.domain.EndSessionUseCase
import chat.homebuddy.core.domain.ObserveSessionUseCase
import chat.homebuddy.core.domain.RefreshProfileUseCase
import chat.homebuddy.core.domain.SignInResult
import chat.homebuddy.core.domain.SignInUseCase
import chat.homebuddy.core.domain.SignOutUseCase
import chat.homebuddy.core.testing.FakeAuthClient
import chat.homebuddy.core.testing.TestConversationsRepository
import chat.homebuddy.core.testing.TestDocumentsRepository
import chat.homebuddy.core.testing.TestProfileRepository
import chat.homebuddy.core.testing.httpError
import chat.homebuddy.core.testing.testAuthRepository
import chat.homebuddy.designsystem.UiText
import chat.homebuddy.designsystem.uiText
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The real ViewModel and use cases on in-memory test repositories, on the plain JVM. */
class SessionViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val authClient = FakeAuthClient()
    private val auth = testAuthRepository(authClient)
    private val profile = TestProfileRepository()
    private val conversations = TestConversationsRepository()
    private val endSession = EndSessionUseCase(auth, profile, TestDocumentsRepository(), conversations)
    private val signIn = SignInUseCase(auth, profile, endSession)

    /** The ViewModel as the app creates it at launch, which also refreshes the profile. */
    private fun launch() = SessionViewModel(
        ObserveSessionUseCase(auth, profile),
        signIn,
        SignOutUseCase(endSession, auth),
        RefreshProfileUseCase(auth, profile, endSession),
    )

    private suspend fun signedIn() = assertEquals(SignInResult.SignedIn, signIn(CODE))

    private fun signInScreen(message: UiText? = null) = AppUiState.SignedOut(SignInUiState.Idle(message))

    private fun signInError(@StringRes message: Int) = AppUiState.SignedOut(SignInUiState.Error(uiText(message)))

    @Test
    fun `a new install shows sign-in`() = runTest {
        assertEquals(signInScreen(), launch().state.value)
    }

    @Test
    fun `a phone that's signed in opens the app`() = runTest {
        signedIn()

        assertEquals(AppUiState.Main, launch().state.value)
    }

    @Test
    fun `the sign-in screen says why the last session ended`() = runTest {
        mapOf(
            SignOutReason.SESSION_ENDED to R.string.sign_in_session_ended,
            SignOutReason.ACCOUNT_DELETED to R.string.sign_in_account_deleted,
            SignOutReason.NOT_IN_BETA to R.string.sign_in_not_in_beta,
        ).forEach { (reason, message) ->
            auth.clear(reason)

            assertEquals(signInScreen(uiText(message)), launch().state.value)
        }
    }

    @Test
    fun `an account removed from the beta is signed out at launch`() = runTest {
        signedIn()
        profile.failure = httpError(403)

        assertEquals(signInScreen(uiText(R.string.sign_in_not_in_beta)), launch().state.value)
    }

    @Test
    fun `signing in shows progress, then the app`() = runTest {
        val tokens = CompletableDeferred<TokenSet>()
        authClient.exchange = { tokens.await() }
        val viewModel = launch()

        viewModel.onSignInResult(CODE)

        assertEquals(AppUiState.SignedOut(SignInUiState.InProgress), viewModel.state.value)
        tokens.complete(TokenSet("access-1", "id-1", "refresh-1", expiresAtMillis = null))
        assertEquals(AppUiState.Main, viewModel.state.value)
    }

    @Test
    fun `leaving the app after signing in keeps you signed in`() = runTest {
        val activity = ViewModelStore()
        val viewModel = ViewModelProvider.create(activity, viewModelFactory { initializer { launch() } })[SessionViewModel::class]
        viewModel.onSignInResult(CODE)
        assertTrue("signed in", viewModel.state.value is AppUiState.Main)

        // Back from the signed-in app closes it (see SignedInScopeTest), which clears its ViewModels.
        activity.clear()

        assertTrue("the session is still stored", auth.isSignedIn())
        assertTrue("reopening goes straight into the app", launch().state.value is AppUiState.Main)
    }

    @Test
    fun `a new account goes on to set up its home`() = runTest {
        profile.server = profile.server.copy(households = emptyList())
        val viewModel = launch()

        viewModel.onSignInResult(CODE)

        assertEquals(AppUiState.HomeSetup, viewModel.state.value)
    }

    @Test
    fun `a failed sign-in says why`() = runTest {
        val viewModel = launch()

        viewModel.onSignInResult(SignInPageResult.Failed)
        assertEquals(signInError(R.string.sign_in_failed), viewModel.state.value)

        profile.failure = httpError(403)
        viewModel.onSignInResult(CODE)
        assertEquals(signInError(R.string.sign_in_not_in_beta), viewModel.state.value)

        authClient.exchange = { throw IOException("No connection") }
        viewModel.onSignInResult(CODE)
        assertEquals(signInError(R.string.error_offline), viewModel.state.value)
    }

    @Test
    fun `closing the sign-in page leaves no error behind`() = runTest {
        val viewModel = launch()
        viewModel.onSignInResult(SignInPageResult.Failed)

        viewModel.onSignInResult(SignInPageResult.Cancelled)

        assertEquals(signInScreen(), viewModel.state.value)
    }

    @Test
    fun `trying again clears the error and opens the sign-in page`() = runTest {
        val viewModel = launch()
        viewModel.onSignInResult(SignInPageResult.Failed)

        assertTrue(viewModel.onSignInClick())

        assertEquals(signInScreen(), viewModel.state.value)
    }

    @Test
    fun `when the profile can't be fetched, the button retries it instead of opening sign-in`() = runTest {
        signedIn()
        // As after an update that rebuilt the cache: still signed in, but no profile on this phone.
        profile.clear()
        profile.failure = IOException("No connection")
        val viewModel = launch()
        assertEquals(AppUiState.Loading(uiText(R.string.error_offline)), viewModel.state.value)
        profile.failure = null

        assertFalse(viewModel.onSignInClick())

        assertEquals(AppUiState.Main, viewModel.state.value)
    }

    @Test
    fun `signing out hands the screen the logout page to open, once`() = runTest {
        signedIn()
        val viewModel = launch()

        viewModel.onSignOutClick()

        assertEquals(signInScreen(), viewModel.state.value)
        assertEquals("https://auth.example.com/logout", viewModel.pendingLogoutUrl.value)
        viewModel.onLogoutPageOpened()
        assertNull(viewModel.pendingLogoutUrl.value)
    }

    @Test
    fun `tapping Sign out twice signs out once`() = runTest {
        signedIn()
        val wiping = CompletableDeferred<Unit>()
        conversations.whileWiping = { wiping.await() }
        val viewModel = launch()

        viewModel.onSignOutClick()
        viewModel.onSignOutClick()
        wiping.complete(Unit)

        assertEquals("https://auth.example.com/logout", viewModel.pendingLogoutUrl.value)
        assertEquals(1, authClient.logoutPageRequests)
    }

    @Test
    fun `failed sign-out shows a retry that finishes cleanup before showing sign-in`() = runTest {
        signedIn()
        val viewModel = launch()
        conversations.whileWiping = { throw IOException("disk unavailable") }

        viewModel.onSignOutClick()

        assertEquals(AppUiState.Loading(uiText(R.string.sign_out_cleanup_failed)), viewModel.state.value)
        assertNull(viewModel.pendingLogoutUrl.value)
        assertTrue(auth.pendingCleanup() != null)
        conversations.whileWiping = {}

        assertFalse(viewModel.onSignInClick())

        assertEquals(signInScreen(), viewModel.state.value)
        assertNull(auth.pendingCleanup())
    }

    @Test
    fun `startup cleanup failures can be retried without opening the sign-in page`() = runTest {
        signedIn()
        conversations.whileWiping = { throw IOException("disk unavailable") }
        runCatching { endSession(SignOutReason.ACCOUNT_DELETED) }

        val viewModel = launch()
        assertEquals(AppUiState.Loading(uiText(R.string.sign_out_cleanup_failed)), viewModel.state.value)

        conversations.whileWiping = {}
        assertFalse(viewModel.onSignInClick())

        assertEquals(signInScreen(uiText(R.string.sign_in_account_deleted)), viewModel.state.value)
        assertNull(auth.pendingCleanup())
    }

    private companion object {
        val CODE = SignInPageResult.Code("authorization-response")
    }
}
