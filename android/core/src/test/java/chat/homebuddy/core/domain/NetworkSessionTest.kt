package chat.homebuddy.core.domain

import chat.homebuddy.core.auth.SignInPageResult
import chat.homebuddy.core.auth.SignOutReason
import chat.homebuddy.core.testing.TestConversationsRepository
import chat.homebuddy.core.testing.TestDocumentsRepository
import chat.homebuddy.core.testing.TestProfileRepository
import chat.homebuddy.core.testing.testAuthRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the HTTP client's session does when the server rejects it (see AuthenticatedClientTest for when that happens). */
class NetworkSessionTest {
    private val auth = testAuthRepository()
    private val profile = TestProfileRepository()
    private val documents = TestDocumentsRepository()
    private val conversations = TestConversationsRepository()
    private val session = NetworkSession(auth, EndSessionUseCase(auth, profile, documents, conversations))

    private val code = SignInPageResult.Code("authorization-response")

    @Test
    fun `requests use this phone's access token`() = runTest {
        SignInUseCase(auth, profile)(code)

        assertEquals("access-1", session.accessToken(forceRefresh = false))
        assertEquals("access-2", session.accessToken(forceRefresh = true))
    }

    @Test
    fun `a session the server rejects ends with a reason and wipes this phone`() = runTest {
        SignInUseCase(auth, profile)(code)

        session.onSessionRejected()

        assertEquals(SessionState.SignedOut(SignOutReason.SESSION_ENDED), ObserveSessionUseCase(auth, profile)().first())
        assertNull(profile.profile.first())
        assertTrue(documents.wiped)
        assertTrue(conversations.wiped)
    }

    @Test
    fun `a rejection while a sign-in is being set up only drops its tokens`() = runTest {
        auth.exchangeCode(code)

        session.onSessionRejected()

        assertNull(auth.accessToken())
        assertEquals(SessionState.SignedOut(reason = null), ObserveSessionUseCase(auth, profile)().first())
        assertFalse(documents.wiped)
    }
}
