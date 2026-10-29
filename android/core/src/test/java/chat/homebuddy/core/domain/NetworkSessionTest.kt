package chat.homebuddy.core.domain

import chat.homebuddy.core.auth.SignInPageResult
import chat.homebuddy.core.auth.SignOutReason
import chat.homebuddy.core.auth.TokenSet
import chat.homebuddy.core.testing.FakeAuthClient
import chat.homebuddy.core.testing.TestConversationsRepository
import chat.homebuddy.core.testing.TestDocumentsRepository
import chat.homebuddy.core.testing.TestProfileRepository
import chat.homebuddy.core.testing.testAuthRepository
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the HTTP client's session does when the server rejects it (see AuthenticatedClientTest for when that happens). */
class NetworkSessionTest {
    private val client = FakeAuthClient()
    private val auth = testAuthRepository(client)
    private val profile = TestProfileRepository()
    private val documents = TestDocumentsRepository()
    private val conversations = TestConversationsRepository()
    private val endSession = EndSessionUseCase(auth, profile, documents, conversations)
    private val session = NetworkSession(auth, endSession)

    private val code = SignInPageResult.Code("authorization-response")

    @Test
    fun `requests use this phone's access token`() = runTest {
        SignInUseCase(auth, profile, endSession)(code)

        val credentials = session.credentials()
        assertEquals("access-1", credentials.accessToken)
        assertEquals("access-2", session.refresh(credentials)?.accessToken)
    }

    @Test
    fun `a session the server rejects ends with a reason and wipes this phone`() = runTest {
        SignInUseCase(auth, profile, endSession)(code)

        session.onSessionRejected(session.credentials())

        assertEquals(SessionState.SignedOut(SignOutReason.SESSION_ENDED), ObserveSessionUseCase(auth, profile)().first())
        assertNull(profile.profile.first())
        assertTrue(documents.wiped)
        assertTrue(conversations.wiped)
    }

    @Test
    fun `a rejection while a sign-in is being set up only drops its tokens`() = runTest {
        auth.exchangeCode(code)

        session.onSessionRejected(session.credentials())

        assertNull(auth.accessToken())
        assertEquals(SessionState.SignedOut(reason = null), ObserveSessionUseCase(auth, profile)().first())
        assertFalse(documents.wiped)
    }

    @Test
    fun `an old rejection cannot end a later sign-in to the same account`() = runTest {
        val signIn = SignInUseCase(auth, profile, endSession)
        signIn(code)
        val old = session.credentials()
        endSession()
        signIn(code)
        val wipes = conversations.wipes

        assertNull(session.refresh(old))
        session.onSessionRejected(old)

        assertTrue(auth.isSignedIn())
        assertEquals("access-1", session.credentials().accessToken)
        assertEquals(wipes, conversations.wipes)
    }

    @Test
    fun `a rejected old token cannot clear a newer credential in the same session`() = runTest {
        SignInUseCase(auth, profile, endSession)(code)
        val old = session.credentials()
        val fresh = session.refresh(old)!!

        session.onSessionRejected(old)

        assertTrue(auth.isSignedIn())
        assertEquals(fresh, session.credentials())
        assertFalse(documents.wiped)
    }

    @Test
    fun `rejection rechecks the credential after waiting for the transition lock`() = runTest {
        SignInUseCase(auth, profile, endSession)(code)
        val old = session.credentials()
        val rejection = auth.withSessionTransition {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { session.onSessionRejected(old) }
            assertFalse(pending.isCompleted)
            session.refresh(old)
            pending
        }
        rejection.await()

        assertTrue(auth.isSignedIn())
        assertEquals("access-2", session.credentials().accessToken)
        assertFalse(documents.wiped)
    }

    @Test
    fun `parallel rejected requests reuse one completed refresh`() = runTest {
        SignInUseCase(auth, profile, endSession)(code)
        val rejected = session.credentials()
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var refreshes = 0
        client.refresh = {
            refreshes++
            started.complete(Unit)
            resume.await()
            TokenSet("renewed", null, null, null)
        }
        val first = async { session.refresh(rejected) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { session.refresh(rejected) }
        resume.complete(Unit)

        assertEquals(first.await(), second.await())
        assertEquals(1, refreshes)
        assertEquals("renewed", session.credentials().accessToken)
    }

    @Test
    fun `a rejected refresh can still end the exact saved session that requested it`() = runTest {
        SignInUseCase(auth, profile, endSession)(code)
        client.refresh = { null }
        val rejected = session.refresh(session.credentials())!!
        assertNull(rejected.accessToken)

        session.onSessionRejected(rejected)

        assertFalse(auth.isSignedIn())
        assertTrue(documents.wiped)
        assertTrue(conversations.wiped)
    }

    @Test
    fun `a stale rejection cannot drop another pending sign-in's tokens`() = runTest {
        auth.exchangeCode(code)
        val old = session.credentials()
        client.exchange = { TokenSet("new-access", "new-id", "new-refresh", null) }
        auth.exchangeCode(code)

        session.onSessionRejected(old)

        assertEquals("new-access", session.credentials().accessToken)
        assertFalse(documents.wiped)
    }

    @Test
    fun `cleanup after a valid rejection remains retryable if deletion fails`() = runTest {
        SignInUseCase(auth, profile, endSession)(code)
        val rejected = session.credentials()
        documents.whileWiping = { throw IOException("disk unavailable") }

        assertTrue(runCatching { session.onSessionRejected(rejected) }.exceptionOrNull() is IOException)
        assertFalse(auth.isSignedIn())
        assertTrue(auth.pendingCleanup() != null)

        documents.whileWiping = {}
        endSession.reconcile()

        assertNull(auth.pendingCleanup())
        assertEquals(SessionState.SignedOut(SignOutReason.SESSION_ENDED), ObserveSessionUseCase(auth, profile)().first())
    }

    @Test
    fun `parallel rejection events clear the matching session only once`() = runTest {
        SignInUseCase(auth, profile, endSession)(code)
        val rejected = session.credentials()
        val wiping = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        documents.whileWiping = { wiping.complete(Unit); resume.await() }
        val first = async { session.onSessionRejected(rejected) }
        wiping.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { session.onSessionRejected(rejected) }
        resume.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, documents.wipes)
        assertEquals(1, conversations.wipes)
        assertFalse(auth.isSignedIn())
    }
}
