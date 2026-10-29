package chat.homebuddy.core.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import chat.homebuddy.core.testing.XorCipher
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val store = AuthStore(dataStore, XorCipher(), StandardTestDispatcher(testScheduler))

        store.saveRefreshToken("secret-refresh")

        assertEquals(StoredAuth(refreshToken = "secret-refresh", signOutReason = null), store.stored.first())
        val raw = dataStore.data.first()[stringPreferencesKey("refresh_token")].orEmpty()
        assertFalse(raw.contains("secret-refresh"))
    }

    @Test
    fun `clearing forgets the token and remembers why`() = runTest {
        val store = AuthStore(dataStore(), XorCipher(), StandardTestDispatcher(testScheduler))
        store.saveRefreshToken("token")

        store.clear(SignOutReason.ACCOUNT_DELETED)

        assertEquals(StoredAuth(refreshToken = null, signOutReason = SignOutReason.ACCOUNT_DELETED), store.stored.first())
    }

    @Test
    fun `signing in again forgets the last sign-out reason`() = runTest {
        val store = AuthStore(dataStore(), XorCipher(), StandardTestDispatcher(testScheduler))
        store.clear(SignOutReason.SESSION_ENDED)

        store.saveRefreshToken("token")

        assertEquals(null, store.stored.first().signOutReason)
    }

    @Test
    fun `an unreadable token is removed and its session enters durable cleanup`() = runTest {
        val dataStore = dataStore()
        AuthStore(dataStore, XorCipher(), StandardTestDispatcher(testScheduler)).saveRefreshToken("token")

        val afterRestore = AuthStore(dataStore, XorCipher(broken = true), StandardTestDispatcher(testScheduler))

        val recovered = afterRestore.stored.first()
        assertNull(recovered.refreshToken)
        assertEquals(SignOutReason.SESSION_ENDED, recovered.signOutReason)
        assertEquals(afterRestore.cleanupOwner(), recovered.pendingCleanup)
        assertTrue(recovered.pendingCleanup != null)
        assertNull(dataStore.data.first()[stringPreferencesKey("refresh_token")])
        assertEquals(recovered, afterRestore.stored.first())
        afterRestore.completeCleanup(recovered.pendingCleanup!!)
        afterRestore.saveRefreshToken("new-token")
        assertEquals("new-token", AuthStore(dataStore, XorCipher(), StandardTestDispatcher(testScheduler)).stored.first().refreshToken)
    }

    @Test
    fun `cleanup ownership and the first reason outlive credentials`() = runTest {
        val store = AuthStore(dataStore(), XorCipher(), StandardTestDispatcher(testScheduler))
        store.saveRefreshToken("token", "session-a")

        assertTrue(store.beginCleanup("session-a", SignOutReason.SESSION_ENDED))
        assertTrue(store.beginCleanup("session-a", SignOutReason.ACCOUNT_DELETED))
        store.clearSignOutReason()

        assertEquals(StoredAuth(null, SignOutReason.SESSION_ENDED, "session-a"), store.stored.first())
        assertEquals("session-a", store.cleanupOwner())
        store.completeCleanup("session-a")
        assertEquals(StoredAuth(null, SignOutReason.SESSION_ENDED), store.stored.first())
        assertNull(store.cleanupOwner())
    }

    @Test
    fun `a user initiated sign-out keeps its null reason when retried`() = runTest {
        val store = AuthStore(dataStore(), XorCipher(), StandardTestDispatcher(testScheduler))
        store.saveRefreshToken("token", "session-a")

        store.beginCleanup("session-a", null)
        store.beginCleanup("session-a", SignOutReason.ACCOUNT_DELETED)

        assertNull(store.stored.first().signOutReason)
    }

    @Test
    fun `credentials cannot be saved while their cleanup is pending`() = runTest {
        val store = AuthStore(dataStore(), XorCipher(), StandardTestDispatcher(testScheduler))
        store.saveRefreshToken("token", "session-a")
        store.beginCleanup("session-a", null)

        assertTrue(runCatching { store.saveRefreshToken("new-token", "session-b") }.exceptionOrNull() is IOException)
        assertEquals("session-a", store.stored.first().pendingCleanup)
        assertNull(store.stored.first().refreshToken)
    }

    @Test
    fun `a stale cleanup cannot remove a new sign-in even to the same account`() = runTest {
        val store = AuthStore(dataStore(), XorCipher(), StandardTestDispatcher(testScheduler))
        store.saveRefreshToken("same-account-token", "session-a")
        store.beginCleanup("session-a", SignOutReason.SESSION_ENDED)
        store.completeCleanup("session-a")
        store.saveRefreshToken("same-account-token", "session-b")

        assertFalse(store.beginCleanup("session-a", SignOutReason.ACCOUNT_DELETED))
        store.completeCleanup("session-a")

        assertEquals("same-account-token", store.stored.first().refreshToken)
        assertEquals("session-b", store.cleanupOwner())
        assertNull(store.stored.first().signOutReason)
    }

    @Test
    fun `rotating a refresh token preserves the sign-in identity`() = runTest {
        val store = AuthStore(dataStore(), XorCipher(), StandardTestDispatcher(testScheduler))
        store.saveRefreshToken("token", "session-a")

        store.saveRefreshToken("rotated")

        assertEquals("session-a", store.cleanupOwner())
    }

    @Test
    fun `sessions from an older app version receive a stable cleanup identity`() = runTest {
        val dataStore = dataStore()
        val store = AuthStore(dataStore, XorCipher(), StandardTestDispatcher(testScheduler))
        store.saveRefreshToken("token")
        dataStore.edit { it.remove(stringPreferencesKey("session_id")) }

        val owner = store.cleanupOwner()

        assertTrue(owner != null)
        assertEquals(owner, store.cleanupOwner())
        assertTrue(store.beginCleanup(owner!!, SignOutReason.SESSION_ENDED))
    }
}
