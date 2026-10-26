package chat.homebuddy.core.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import chat.homebuddy.core.testing.FakeAuthClient
import chat.homebuddy.core.testing.XorCipher
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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
        AuthStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "auth.preferences_pb") }, XorCipher())

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
}
