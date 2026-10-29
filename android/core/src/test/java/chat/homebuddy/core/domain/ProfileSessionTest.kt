package chat.homebuddy.core.domain

import chat.homebuddy.core.auth.AccessTokenCache
import chat.homebuddy.core.auth.SignInPageResult
import chat.homebuddy.core.auth.TokenSet
import chat.homebuddy.core.data.profile.CachedProfileRepository
import chat.homebuddy.core.data.profile.TestProfileLocalDataSource
import chat.homebuddy.core.data.profile.TestProfileRemoteDataSource
import chat.homebuddy.core.network.HouseholdDto
import chat.homebuddy.core.network.UserDto
import chat.homebuddy.core.testing.FakeAuthClient
import chat.homebuddy.core.testing.TestConversationsRepository
import chat.homebuddy.core.testing.TestDocumentsRepository
import chat.homebuddy.core.testing.httpError
import chat.homebuddy.core.testing.testAuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real auth and profile repositories, including the sign-in cleanup and logout paths. */
class ProfileSessionTest {
    private val sessions = AccessTokenCache()
    private val client = FakeAuthClient()
    private val auth = testAuthRepository(client, sessions)
    private val remote = TestProfileRemoteDataSource()
    private val profile = CachedProfileRepository(remote, TestProfileLocalDataSource(), sessions)
    private val conversations = TestConversationsRepository()
    private val endSession = EndSessionUseCase(auth, profile, TestDocumentsRepository(), conversations)
    private val signIn = SignInUseCase(auth, profile, endSession)
    private val refresh = RefreshProfileUseCase(auth, profile, endSession)
    private val code = SignInPageResult.Code("authorization-response")

    @Test
    fun `a cancelled old bootstrap cannot clear a newer successful sign-in`() = runTest {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var holdFirstBootstrap = true
        remote.whileCalling = {
            if (it == "bootstrap" && holdFirstBootstrap) {
                holdFirstBootstrap = false
                started.complete(Unit)
                withContext(NonCancellable) { response.await() }
            }
        }
        val oldSignIn = async { runCatching { signIn(code) } }
        started.await()

        signInAnotherAccount()
        response.complete(Unit)

        assertTrue(oldSignIn.await().exceptionOrNull() is CancellationException)
        assertTrue(auth.isSignedIn())
        assertEquals("new-access", auth.accessToken())
        assertEquals(2, profile.profile.first()?.user?.id)
        assertEquals(listOf(20), profile.profile.first()?.households?.map { it.id })
    }

    @Test
    fun `a late forbidden response cannot sign out the next account`() = runTest {
        assertEquals(SignInResult.SignedIn, signIn(code))
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        remote.failure = httpError(403)
        remote.whileCalling = {
            if (it == "me") {
                started.complete(Unit)
                withContext(NonCancellable) { response.await() }
            }
        }
        val oldRefresh = async { runCatching { refresh() } }
        started.await()

        endSession()
        remote.failure = null
        signInAnotherAccount()
        response.complete(Unit)

        assertTrue(oldRefresh.await().exceptionOrNull() is CancellationException)
        assertTrue(auth.isSignedIn())
        assertEquals(2, profile.profile.first()?.user?.id)
    }

    @Test
    fun `finishing old logout cleanup cannot clear the new account's profile`() = runTest {
        assertEquals(SignInResult.SignedIn, signIn(code))
        val wiping = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        conversations.whileWiping = {
            wiping.complete(Unit)
            resume.await()
        }
        val logout = async { endSession() }
        wiping.await()

        val newSignIn = async(start = CoroutineStart.UNDISPATCHED) { signInAnotherAccount() }
        assertTrue("sign-in waits for the previous session's global cleanup", !newSignIn.isCompleted)
        resume.complete(Unit)
        logout.await()
        newSignIn.await()

        assertTrue(auth.isSignedIn())
        assertEquals("new-access", auth.accessToken())
        assertEquals(2, profile.profile.first()?.user?.id)
        assertEquals(listOf(20), profile.profile.first()?.households?.map { it.id })
    }

    private suspend fun signInAnotherAccount() {
        client.exchange = { TokenSet("new-access", "new-id", "new-refresh", null) }
        remote.user = UserDto(2, "new-sub", "new@example.com", "New account")
        remote.households = listOf(HouseholdDto(20, "New home", "10001", "condo", "owner"))
        assertEquals(SignInResult.SignedIn, signIn(code))
    }
}
