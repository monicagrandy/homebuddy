package chat.homebuddy.core.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import chat.homebuddy.core.testing.FakeAuthClient
import chat.homebuddy.core.testing.XorCipher
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val client = FakeAuthClient()
    private val code = SignInPageResult.Code("authorization-response")

    private fun TestScope.store() =
        AuthStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "auth.preferences_pb") }, XorCipher(), StandardTestDispatcher(testScheduler))

    @Test
    fun `a sign-in isn't kept until it's saved`() = runTest {
        val store = store()
        val auth = AuthRepository(client, store, AccessTokenCache())

        auth.exchangeCode(code)
        assertFalse(auth.isSignedIn())
        assertEquals("access-1", auth.accessToken())

        auth.keepSignedIn()
        assertTrue(auth.isSignedIn())
        assertEquals("refresh-1", store.stored.first().refreshToken)
    }

    @Test
    fun `an access token that expires before the sign-in is saved keeps its refresh token`() = runTest {
        val store = store()
        val auth = AuthRepository(client, store, AccessTokenCache())
        client.exchange = { TokenSet("access-1", "id-1", "refresh-1", expiresAtMillis = 0) }

        auth.exchangeCode(code)
        assertEquals("access-2", auth.accessToken())
        auth.keepSignedIn()

        assertEquals("refresh-1", store.stored.first().refreshToken)
    }

    @Test
    fun `a rotated refresh token is saved`() = runTest {
        val store = store()
        val auth = AuthRepository(client, store, AccessTokenCache())
        auth.exchangeCode(code)
        auth.keepSignedIn()
        client.refresh = { TokenSet("access-2", "id-2", refreshToken = "refresh-2", expiresAtMillis = null) }

        auth.accessToken(forceRefresh = true)

        assertEquals("refresh-2", store.stored.first().refreshToken)
    }

    @Test
    fun `a refresh token Cognito rejects gives no access token`() = runTest {
        val auth = AuthRepository(client, store(), AccessTokenCache())
        auth.exchangeCode(code)
        auth.keepSignedIn()
        client.refresh = { null }

        assertNull(auth.accessToken(forceRefresh = true))
    }

    @Test
    fun `forgetting an unsaved sign-in drops its tokens`() = runTest {
        val auth = AuthRepository(client, store(), AccessTokenCache())
        auth.exchangeCode(code)

        auth.forgetUnsavedSignIn()

        assertNull(auth.accessToken())
    }

    @Test
    fun `clearing signs this phone out and remembers why`() = runTest {
        val auth = AuthRepository(client, store(), AccessTokenCache())
        auth.exchangeCode(code)
        auth.keepSignedIn()

        auth.clear(SignOutReason.ACCOUNT_DELETED)

        assertEquals(AuthState.SignedOut(SignOutReason.ACCOUNT_DELETED), auth.state.first())
        assertNull(auth.accessToken())
    }

    @Test
    fun `a refresh finishing after sign-out cannot restore the session`() = runTest {
        val store = store()
        val auth = AuthRepository(client, store, AccessTokenCache())
        auth.exchangeCode(code)
        auth.keepSignedIn()
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        client.refresh = { token ->
            assertEquals("refresh-1", token)
            started.complete(Unit)
            resume.await()
            TokenSet("old-renewed", null, "old-rotated", expiresAtMillis = null)
        }
        val result = async { runCatching { auth.accessToken(forceRefresh = true) } }
        started.await()

        auth.clear(SignOutReason.ACCOUNT_DELETED)
        resume.complete(Unit)

        assertTrue(result.await().exceptionOrNull() is IOException)
        assertEquals(StoredAuth(null, SignOutReason.ACCOUNT_DELETED), store.stored.first())
        assertNull(auth.accessToken())
    }

    @Test
    fun `an old refresh cannot overwrite another account after sign-out and sign-in`() = runTest {
        val store = store()
        val auth = AuthRepository(client, store, AccessTokenCache())
        auth.exchangeCode(code)
        auth.keepSignedIn()
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        client.refresh = { token ->
            assertEquals("refresh-1", token)
            started.complete(Unit)
            resume.await()
            TokenSet("old-renewed", null, "old-rotated", expiresAtMillis = null)
        }
        val result = async { runCatching { auth.accessToken(forceRefresh = true) } }
        started.await()

        auth.clear(null)
        client.exchange = { TokenSet("new-access", "new-id", "new-refresh", expiresAtMillis = null) }
        auth.exchangeCode(code)
        auth.keepSignedIn()
        client.refresh = { token ->
            assertEquals("new-refresh", token)
            TokenSet("new-renewed", null, "new-rotated", expiresAtMillis = null)
        }
        assertEquals("new-renewed", auth.accessToken(forceRefresh = true))
        resume.complete(Unit)

        assertTrue(result.await().exceptionOrNull() is IOException)
        assertEquals("new-renewed", auth.accessToken())
        assertEquals("new-rotated", store.stored.first().refreshToken)
        assertTrue(auth.isSignedIn())
    }

    @Test
    fun `a pending sign-in refreshes its own credentials without saving over the stored account`() = runTest {
        val store = store()
        store.saveRefreshToken("older-account")
        val auth = AuthRepository(client, store, AccessTokenCache())
        auth.exchangeCode(code)
        client.refresh = { token ->
            assertEquals("refresh-1", token)
            TokenSet("renewed", null, "rotated", expiresAtMillis = null)
        }

        assertEquals("renewed", auth.accessToken(forceRefresh = true))
        assertEquals("older-account", store.stored.first().refreshToken)

        auth.keepSignedIn()
        assertEquals("rotated", store.stored.first().refreshToken)
    }

    @Test
    fun `a restored session saves rotated refresh tokens`() = runTest {
        val store = store()
        store.saveRefreshToken("restored-refresh")
        val auth = AuthRepository(client, store, AccessTokenCache())
        client.refresh = { token ->
            assertEquals("restored-refresh", token)
            TokenSet("renewed", null, "rotated", expiresAtMillis = null)
        }

        assertEquals("renewed", auth.accessToken())
        assertEquals("rotated", store.stored.first().refreshToken)
    }

    @Test
    fun `a rejected pending sign-in cannot fall back to the stored account`() = runTest {
        val store = store()
        store.saveRefreshToken("older-account")
        val auth = AuthRepository(client, store, AccessTokenCache())
        auth.exchangeCode(code)
        client.refresh = { token ->
            assertEquals("refresh-1", token)
            null
        }

        assertNull(auth.accessToken(forceRefresh = true))
        assertNull(auth.accessToken())
        assertEquals("older-account", store.stored.first().refreshToken)
    }
}
