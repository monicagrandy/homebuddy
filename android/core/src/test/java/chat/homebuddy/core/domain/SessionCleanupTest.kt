package chat.homebuddy.core.domain

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import chat.homebuddy.core.auth.AccessTokenCache
import chat.homebuddy.core.auth.AuthRepository
import chat.homebuddy.core.auth.AuthState
import chat.homebuddy.core.auth.AuthStore
import chat.homebuddy.core.auth.SecretCipher
import chat.homebuddy.core.auth.SignInPageResult
import chat.homebuddy.core.auth.SignOutReason
import chat.homebuddy.core.testing.FakeAuthClient
import chat.homebuddy.core.testing.TestConversationsRepository
import chat.homebuddy.core.testing.TestDocumentsRepository
import chat.homebuddy.core.testing.TestProfileRepository
import chat.homebuddy.core.testing.XorCipher
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionCleanupTest {
    @get:Rule val folder = TemporaryFolder()
    private val client = FakeAuthClient()
    private val profile = TestProfileRepository()
    private val documents = TestDocumentsRepository()
    private val conversations = TestConversationsRepository()
    private val code = SignInPageResult.Code("authorization-response")

    private fun TestScope.auth(scope: CoroutineScope = backgroundScope, cipher: SecretCipher = XorCipher()): AuthRepository = AuthRepository(
        client,
        AuthStore(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "auth.preferences_pb") }, cipher, StandardTestDispatcher(testScheduler)),
        AccessTokenCache(),
    )

    private fun endSession(auth: AuthRepository) = EndSessionUseCase(auth, profile, documents, conversations)

    private suspend fun signIn(auth: AuthRepository, end: EndSessionUseCase) =
        assertEquals(SignInResult.SignedIn, SignInUseCase(auth, profile, end)(code))

    @Test
    fun `an unreadable token after restart wipes account data before another sign-in`() = runTest {
        val storeScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val original = auth(storeScope)
        signIn(original, endSession(original))
        storeScope.cancel()
        storeScope.coroutineContext.job.join()
        val restored = auth(cipher = XorCipher(broken = true))
        var refreshes = 0
        client.refresh = { refreshes++; error("An unreadable session must not refresh") }

        assertEquals(AuthState.CleaningUp, restored.state.first())
        assertTrue(RefreshProfileUseCase(restored, profile, endSession(restored))())

        assertEquals(0, refreshes)
        assertNull(profile.profile.first())
        assertTrue(documents.wiped)
        assertEquals(0, documents.reconciliations)
        assertTrue(conversations.wiped)
        assertNull(restored.pendingCleanup())
        assertEquals(AuthState.SignedOut(SignOutReason.SESSION_ENDED), restored.state.first())
    }

    @Test
    fun `cleanup retries after credentials are cleared and preserves the first reason`() = runTest {
        val auth = auth()
        val end = endSession(auth)
        signIn(auth, end)
        documents.whileWiping = { throw IOException("disk unavailable") }

        assertTrue(runCatching { end(SignOutReason.SESSION_ENDED) }.exceptionOrNull() is IOException)
        assertFalse(auth.isSignedIn())
        assertNull(auth.accessToken())
        assertEquals(AuthState.CleaningUp, auth.state.first())
        assertEquals(SessionState.Loading, ObserveSessionUseCase(auth, profile)().first())
        val owner = auth.pendingCleanup()
        assertTrue(owner != null)

        documents.whileWiping = {}
        end(SignOutReason.ACCOUNT_DELETED)

        assertEquals(2, documents.wipes)
        assertTrue(conversations.wiped)
        assertNull(auth.pendingCleanup())
        assertEquals(AuthState.SignedOut(SignOutReason.SESSION_ENDED), auth.state.first())
    }

    @Test
    fun `a failure before the profile is cleared also leaves retryable cleanup`() = runTest {
        val auth = auth()
        val end = endSession(auth)
        signIn(auth, end)
        profile.whileClearing = { throw IllegalStateException("database unavailable") }

        assertTrue(runCatching { end() }.exceptionOrNull() is IOException)
        assertNull(auth.accessToken())
        assertTrue(auth.pendingCleanup() != null)

        profile.whileClearing = {}
        end(SignOutReason.SESSION_ENDED)

        assertNull(profile.profile.first())
        assertEquals(AuthState.SignedOut(null), auth.state.first())
    }

    @Test
    fun `startup reopens persisted cleanup and finishes it without a refresh token`() = runTest {
        val storeScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val original = auth(storeScope)
        val end = endSession(original)
        signIn(original, end)
        conversations.whileWiping = { throw IOException("interrupted cleanup") }
        assertTrue(runCatching { end(SignOutReason.ACCOUNT_DELETED) }.exceptionOrNull() is IOException)
        val owner = original.pendingCleanup()
        storeScope.cancel()
        storeScope.coroutineContext.job.join()

        // New DataStore, cache, auth repository and use case: no in-memory ownership survives.
        val restored = auth()
        assertEquals(owner, restored.pendingCleanup())
        assertFalse(restored.isSignedIn())
        conversations.whileWiping = {}

        assertTrue(RefreshProfileUseCase(restored, profile, endSession(restored))())

        assertNull(restored.pendingCleanup())
        assertEquals(AuthState.SignedOut(SignOutReason.ACCOUNT_DELETED), restored.state.first())
        assertEquals(2, conversations.wipes)
    }

    @Test
    fun `sign-in cannot install credentials or fetch a profile while cleanup is running`() = runTest {
        val auth = auth()
        val end = endSession(auth)
        signIn(auth, end)
        val wiping = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var exchanges = 0
        val exchange = client.exchange
        client.exchange = { exchanges++; exchange() }
        conversations.whileWiping = { wiping.complete(Unit); resume.await() }
        val cleanup = async { end() }
        wiping.await()

        val signingIn = async(start = CoroutineStart.UNDISPATCHED) { SignInUseCase(auth, profile, end)(code) }
        try {
            assertFalse(signingIn.isCompleted)
            assertEquals(0, exchanges)
            assertNull(profile.profile.first())
        } finally {
            resume.complete(Unit)
        }
        cleanup.await()
        assertEquals(SignInResult.SignedIn, signingIn.await())
        assertEquals(1, exchanges)
        assertTrue(auth.isSignedIn())
        assertTrue(profile.profile.first() != null)
    }

    @Test
    fun `a failed pending cleanup blocks sign-in until retry succeeds`() = runTest {
        val auth = auth()
        val end = endSession(auth)
        signIn(auth, end)
        documents.whileWiping = { throw IOException("disk unavailable") }
        runCatching { end(SignOutReason.NOT_IN_BETA) }
        client.exchange = { error("Must not exchange credentials before cleanup finishes") }

        assertEquals(SignInResult.Offline, SignInUseCase(auth, profile, end)(code))
        assertTrue(auth.pendingCleanup() != null)
        assertFalse(auth.isSignedIn())

        documents.whileWiping = {}
        client.exchange = FakeAuthClient().exchange
        signIn(auth, end)
        assertNull(auth.pendingCleanup())
        assertTrue(auth.isSignedIn())
    }

    @Test
    fun `a delayed old cleanup cannot clear a new sign-in to the same account`() = runTest {
        val auth = auth()
        val end = endSession(auth)
        signIn(auth, end)
        val oldOwner = auth.cleanupOwner()!!
        end(SignOutReason.SESSION_ENDED)
        signIn(auth, end)
        assertNotEquals(oldOwner, auth.cleanupOwner())
        val wipes = conversations.wipes

        end.end(oldOwner, SignOutReason.ACCOUNT_DELETED)

        assertTrue(auth.isSignedIn())
        assertEquals("access-1", auth.accessToken())
        assertEquals(wipes, conversations.wipes)
        assertTrue(profile.profile.first() != null)
        assertEquals(AuthState.SignedIn, auth.state.first())
    }

    @Test
    fun `caller cancellation does not interrupt cleanup once it starts`() = runTest {
        val auth = auth()
        val end = endSession(auth)
        signIn(auth, end)
        val wiping = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        conversations.whileWiping = { wiping.complete(Unit); resume.await() }
        val cleanup = async { end(SignOutReason.SESSION_ENDED) }
        wiping.await()

        cleanup.cancel()
        resume.complete(Unit)
        cleanup.join()

        assertNull(auth.pendingCleanup())
        assertTrue(documents.wiped)
        assertTrue(conversations.wiped)
        assertEquals(AuthState.SignedOut(SignOutReason.SESSION_ENDED), auth.state.first())
    }
}
