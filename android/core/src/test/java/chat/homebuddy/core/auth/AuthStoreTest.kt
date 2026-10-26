package chat.homebuddy.core.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import chat.homebuddy.core.testing.XorCipher
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.dataStore() =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "auth.preferences_pb") }

    @Test
    fun `the refresh token round-trips and is never stored as plain text`() = runTest {
        val dataStore = dataStore()
        val store = AuthStore(dataStore, XorCipher())

        store.saveRefreshToken("secret-refresh")

        assertEquals(StoredAuth(refreshToken = "secret-refresh", signOutReason = null), store.stored.first())
        val raw = dataStore.data.first()[stringPreferencesKey("refresh_token")].orEmpty()
        assertFalse(raw.contains("secret-refresh"))
    }

    @Test
    fun `clearing forgets the token and remembers why`() = runTest {
        val store = AuthStore(dataStore(), XorCipher())
        store.saveRefreshToken("token")

        store.clear(SignOutReason.ACCOUNT_DELETED)

        assertEquals(StoredAuth(refreshToken = null, signOutReason = SignOutReason.ACCOUNT_DELETED), store.stored.first())
    }

    @Test
    fun `signing in again forgets the last sign-out reason`() = runTest {
        val store = AuthStore(dataStore(), XorCipher())
        store.clear(SignOutReason.SESSION_ENDED)

        store.saveRefreshToken("token")

        assertEquals(null, store.stored.first().signOutReason)
    }

    @Test
    fun `a token that can no longer be decrypted counts as signed out`() = runTest {
        val dataStore = dataStore()
        AuthStore(dataStore, XorCipher()).saveRefreshToken("token")

        val afterRestore = AuthStore(dataStore, XorCipher(broken = true))

        assertEquals(null, afterRestore.stored.first().refreshToken)
    }
}
