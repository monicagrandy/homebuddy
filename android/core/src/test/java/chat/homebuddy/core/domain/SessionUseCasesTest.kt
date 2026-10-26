package chat.homebuddy.core.domain

import chat.homebuddy.core.auth.SignInPageResult
import chat.homebuddy.core.auth.SignOutReason
import chat.homebuddy.core.auth.TokenSet
import chat.homebuddy.core.testing.FakeAuthClient
import chat.homebuddy.core.testing.TestConversationsRepository
import chat.homebuddy.core.testing.TestDocumentsRepository
import chat.homebuddy.core.testing.TestProfileRepository
import chat.homebuddy.core.testing.httpError
import chat.homebuddy.core.testing.testAuthRepository
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real use cases and AuthRepository, on a fake Cognito and in-memory test repositories. */
class SessionUseCasesTest {
    private val authClient = FakeAuthClient()
    private val auth = testAuthRepository(authClient)
    private val profile = TestProfileRepository()
    private val documents = TestDocumentsRepository()
    private val conversations = TestConversationsRepository()
    private val endSession = EndSessionUseCase(auth, profile, documents, conversations)
    private val observeSession = ObserveSessionUseCase(auth, profile)
    private val signIn = SignInUseCase(auth, profile)
    private val signOut = SignOutUseCase(endSession, auth)
    private val refreshProfile = RefreshProfileUseCase(auth, profile, endSession)
    private val deleteAccount = DeleteAccountUseCase(profile, endSession)

    private suspend fun session() = observeSession().first()

    private suspend fun signedIn() = assertEquals(SignInResult.SignedIn, signIn(CODE))

    @Test
    fun `a new install is signed out`() = runTest {
        assertEquals(SessionState.SignedOut(reason = null), session())
    }

    @Test
    fun `signing in sets up the profile with the new tokens`() = runTest {
        profile.server = profile.server.copy(households = emptyList())

        assertEquals(SignInResult.SignedIn, signIn(CODE))

        val state = session() as SessionState.SignedIn
        assertEquals("pat@example.com", state.user.email)
        assertTrue(state.needsHome)
        assertEquals("id-1" to "access-1", profile.bootstrappedWith)
        assertTrue(auth.isSignedIn())
    }

    @Test
    fun `a sign-in without an ID token fails and keeps nothing`() = runTest {
        authClient.exchange = { TokenSet("access-1", idToken = null, refreshToken = "refresh-1", expiresAtMillis = null) }

        assertEquals(SignInResult.Failed, signIn(CODE))

        assertEquals(SessionState.SignedOut(reason = null), session())
        assertNull(auth.accessToken())
    }

    @Test
    fun `an account outside the beta is not kept signed in`() = runTest {
        profile.failure = httpError(403)

        assertEquals(SignInResult.NotInBeta, signIn(CODE))

        assertEquals(SessionState.SignedOut(reason = null), session())
        assertNull(auth.accessToken())
    }

    @Test
    fun `a sign-in that loses the connection half-way keeps nothing`() = runTest {
        profile.failure = IOException("No connection")

        assertEquals(SignInResult.Offline, signIn(CODE))

        assertEquals(SessionState.SignedOut(reason = null), session())
        assertNull(profile.profile.first())
    }

    @Test
    fun `a sign-in page that failed or was closed changes nothing`() = runTest {
        assertEquals(SignInResult.Failed, signIn(SignInPageResult.Failed))
        assertEquals(SignInResult.Cancelled, signIn(SignInPageResult.Cancelled))

        assertEquals(SessionState.SignedOut(reason = null), session())
    }

    @Test
    fun `trying to sign in again clears the last sign-out reason`() = runTest {
        auth.clear(SignOutReason.ACCOUNT_DELETED)

        signIn(SignInPageResult.Cancelled)

        assertEquals(SessionState.SignedOut(reason = null), session())
    }

    @Test
    fun `signing out wipes this phone and returns the logout page`() = runTest {
        signedIn()

        assertEquals("https://auth.example.com/logout", signOut())

        assertEquals(SessionState.SignedOut(reason = null), session())
        assertNull(profile.profile.first())
        assertTrue(documents.wiped)
        assertTrue(conversations.wiped)
        assertFalse(auth.isSignedIn())
    }

    @Test
    fun `being offline at launch keeps the session and the cached profile`() = runTest {
        signedIn()
        profile.failure = IOException("No connection")

        assertFalse(refreshProfile())

        assertEquals("Elm Street", (session() as SessionState.SignedIn).households.single().name)
    }

    @Test
    fun `a refresh the server rejects is left to the HTTP client, which ends the session`() = runTest {
        signedIn()
        profile.failure = httpError(401)

        assertTrue(refreshProfile())
    }

    @Test
    fun `an account removed from the beta is signed out at launch`() = runTest {
        signedIn()
        profile.failure = httpError(403)

        assertTrue(refreshProfile())

        assertEquals(SessionState.SignedOut(SignOutReason.NOT_IN_BETA), session())
        assertTrue(documents.wiped)
    }

    @Test
    fun `a profile changed on another device shows up after a refresh`() = runTest {
        signedIn()
        profile.server = profile.server.copy(user = profile.server.user.copy(displayName = "Patricia"))

        assertTrue(refreshProfile())

        assertEquals("Patricia", (session() as SessionState.SignedIn).user.displayName)
    }

    @Test
    fun `refreshing while signed out does nothing`() = runTest {
        assertTrue(refreshProfile())

        assertNull(profile.profile.first())
    }

    @Test
    fun `creating a home moves the session on`() = runTest {
        profile.server = profile.server.copy(households = emptyList())
        signedIn()

        profile.createHousehold("Elm Street", "80202", "condo")

        assertFalse((session() as SessionState.SignedIn).needsHome)
    }

    @Test
    fun `deleting the account signs out with a reason`() = runTest {
        signedIn()

        assertEquals(DeleteAccountResult.Deleted, deleteAccount())

        assertTrue(profile.deleted)
        assertEquals(SessionState.SignedOut(SignOutReason.ACCOUNT_DELETED), session())
        assertTrue(documents.wiped)
    }

    @Test
    fun `a deletion that fails keeps the session and says why`() = runTest {
        signedIn()
        profile.failure = httpError(502, "Couldn't finish deleting your sign-in. Please try again.")

        assertEquals(DeleteAccountResult.Failed("Couldn't finish deleting your sign-in. Please try again."), deleteAccount())

        assertTrue(session() is SessionState.SignedIn)
    }

    @Test
    fun `a deletion while offline keeps the session`() = runTest {
        signedIn()
        profile.failure = IOException("No connection")

        assertEquals(DeleteAccountResult.Offline, deleteAccount())

        assertTrue(session() is SessionState.SignedIn)
    }

    private companion object {
        val CODE = SignInPageResult.Code("authorization-response")
    }
}
