package chat.homebuddy.feature.account

import chat.homebuddy.app.MainDispatcherRule
import chat.homebuddy.app.R
import chat.homebuddy.core.auth.AuthState
import chat.homebuddy.core.auth.SignInPageResult
import chat.homebuddy.core.auth.SignOutReason
import chat.homebuddy.core.domain.DeleteAccountUseCase
import chat.homebuddy.core.domain.EndSessionUseCase
import chat.homebuddy.core.domain.ObserveCurrentHouseholdUseCase
import chat.homebuddy.core.domain.SignInUseCase
import chat.homebuddy.core.testing.TestConversationsRepository
import chat.homebuddy.core.testing.TestDocumentsRepository
import chat.homebuddy.core.testing.TestProfileRepository
import chat.homebuddy.core.testing.httpError
import chat.homebuddy.core.testing.testAuthRepository
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The real ViewModel and use cases on in-memory test repositories, on the plain JVM. */
class AccountViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val auth = testAuthRepository()
    private val profile = TestProfileRepository()
    private val endSession = EndSessionUseCase(auth, profile, TestDocumentsRepository(), TestConversationsRepository())

    /** The account screen, with its state collected as the screen would. */
    private fun TestScope.account() = AccountViewModel(profile, ObserveCurrentHouseholdUseCase(profile), DeleteAccountUseCase(profile, endSession))
        .also { viewModel -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} } }

    private suspend fun signedIn() = SignInUseCase(auth, profile, endSession)(SignInPageResult.Code("authorization-response"))

    @Test
    fun `nothing shows until the profile is cached`() = runTest {
        assertNull(account().state.value)
    }

    @Test
    fun `the account shows who's signed in and their home`() = runTest {
        signedIn()

        assertEquals(AccountUiState(name = "Pat", email = "pat@example.com", householdName = "Elm Street"), account().state.value)
    }

    @Test
    fun `an account without a display name shows its email`() = runTest {
        profile.server = profile.server.copy(user = profile.server.user.copy(displayName = null))
        signedIn()

        assertEquals("pat@example.com", account().state.value?.name)
    }

    @Test
    fun `deleting the account signs this phone out`() = runTest {
        signedIn()
        val viewModel = account()

        viewModel.delete()

        assertTrue(profile.deleted)
        assertEquals(AuthState.SignedOut(SignOutReason.ACCOUNT_DELETED), auth.state.first())
        assertNull(viewModel.state.value)
    }

    @Test
    fun `a second tap while deleting does nothing`() = runTest {
        signedIn()
        val viewModel = account()
        val server = CompletableDeferred<Unit>()
        var calls = 0
        profile.whileCalling = {
            calls++
            server.await()
        }

        viewModel.delete()
        assertEquals(true, viewModel.state.value?.deleting)
        viewModel.delete()
        server.complete(Unit)

        assertEquals(1, calls)
    }

    @Test
    fun `a deletion the server couldn't finish says why and keeps the account`() = runTest {
        signedIn()
        val viewModel = account()
        profile.failure = httpError(502, "Couldn't finish deleting your sign-in. Please try again.")

        viewModel.delete()

        val state = viewModel.state.value!!
        assertEquals("Couldn't finish deleting your sign-in. Please try again.".asUiText(), state.error)
        assertFalse(state.deleting)
        assertTrue(auth.isSignedIn())
    }

    @Test
    fun `deleting while offline says so, and trying again clears it`() = runTest {
        signedIn()
        val viewModel = account()
        profile.failure = IOException("No connection")

        viewModel.delete()
        assertEquals(uiText(R.string.error_offline), viewModel.state.value?.error)

        profile.failure = null
        viewModel.delete()
        assertTrue(profile.deleted)
    }
}
