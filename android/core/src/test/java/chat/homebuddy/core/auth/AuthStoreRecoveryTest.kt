package chat.homebuddy.core.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import chat.homebuddy.core.testing.XorCipher
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
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

class AuthStoreRecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun TestScope.dataStore() =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "auth.preferences_pb") }

    @Test
    fun `encryption and decryption run on the injected IO thread`() = runTest {
        val caller = Thread.currentThread()
        val threads = mutableListOf<Thread>()
        val cipher = object : SecretCipher {
            override fun encrypt(plain: ByteArray): ByteArray {
                threads += Thread.currentThread()
                return XorCipher().encrypt(plain)
            }

            override fun decrypt(data: ByteArray): ByteArray {
                threads += Thread.currentThread()
                return XorCipher().decrypt(data)
            }
        }
        Executors.newSingleThreadExecutor { Thread(it, "auth-test-io") }.asCoroutineDispatcher().use { io ->
            val store = AuthStore(dataStore(), cipher, io)
            store.saveRefreshToken("token")
            assertEquals("token", store.stored.first().refreshToken)
        }

        assertEquals(2, threads.size)
        assertTrue(threads.all { it.name == "auth-test-io" && it !== caller })
    }

    @Test
    fun `failed encryption reports an IO error without changing the previous session`() = runTest {
        val dataStore = dataStore()
        val io = StandardTestDispatcher(testScheduler)
        val original = AuthStore(dataStore, XorCipher(), io)
        original.saveRefreshToken("original", "session-a")
        val before = dataStore.data.first()
        val cause = GeneralSecurityException("keystore unavailable")
        val broken = AuthStore(dataStore, object : SecretCipher by XorCipher() {
            override fun encrypt(plain: ByteArray): ByteArray = throw cause
        }, io)

        val failure = runCatching { broken.saveRefreshToken("new", "session-b") }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(generateSequence(failure) { it.cause }.any { it is GeneralSecurityException && it.message == cause.message })
        assertEquals(before, dataStore.data.first())
        assertEquals("original", original.stored.first().refreshToken)
    }

    @Test
    fun `encryption cancellation propagates without changing storage`() = runTest {
        val dataStore = dataStore()
        val cancelled = CancellationException("cancel encryption")
        val store = AuthStore(dataStore, object : SecretCipher by XorCipher() {
            override fun encrypt(plain: ByteArray): ByteArray = throw cancelled
        }, StandardTestDispatcher(testScheduler))
        val before = dataStore.data.first()

        val failure = runCatching { store.saveRefreshToken("token") }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(cancelled.message, failure!!.message)
        assertEquals(before, dataStore.data.first())
    }

    @Test
    fun `decryption cancellation propagates without scheduling sign-out`() = runTest {
        val dataStore = dataStore()
        val io = StandardTestDispatcher(testScheduler)
        AuthStore(dataStore, XorCipher(), io).saveRefreshToken("token", "session-a")
        val before = dataStore.data.first()
        val cancelled = CancellationException("cancel decryption")
        val store = AuthStore(dataStore, object : SecretCipher by XorCipher() {
            override fun decrypt(data: ByteArray): ByteArray = throw cancelled
        }, io)

        val failure = runCatching { store.stored.first() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(cancelled.message, failure!!.message)
        assertEquals(before, dataStore.data.first())
    }

    @Test
    fun `malformed legacy ciphertext receives stable durable cleanup ownership`() = runTest {
        val dataStore = dataStore()
        dataStore.edit { it[stringPreferencesKey("refresh_token")] = "not base64!" }
        val store = AuthStore(dataStore, XorCipher(), StandardTestDispatcher(testScheduler))

        val recovered = store.stored.first()

        assertNull(recovered.refreshToken)
        assertEquals(SignOutReason.SESSION_ENDED, recovered.signOutReason)
        assertTrue(recovered.pendingCleanup != null)
        assertEquals(recovered, store.stored.first())
        assertEquals(recovered.pendingCleanup, store.cleanupOwner())
        assertNull(dataStore.data.first()[stringPreferencesKey("refresh_token")])
    }

    @Test
    fun `pending cleanup prevents encryption from starting`() = runTest {
        val dataStore = dataStore()
        val io = StandardTestDispatcher(testScheduler)
        val original = AuthStore(dataStore, XorCipher(), io)
        original.saveRefreshToken("token", "session-a")
        original.beginCleanup("session-a", SignOutReason.ACCOUNT_DELETED)
        var encryptions = 0
        val store = AuthStore(dataStore, object : SecretCipher by XorCipher() {
            override fun encrypt(plain: ByteArray): ByteArray {
                encryptions++
                return plain
            }
        }, io)

        assertTrue(runCatching { store.saveRefreshToken("new-token") }.exceptionOrNull() is IOException)
        assertEquals(0, encryptions)
        assertEquals(StoredAuth(null, SignOutReason.ACCOUNT_DELETED, "session-a"), store.stored.first())
    }

    @Test
    fun `a delayed failed read cannot erase a rotated refresh token`() = recoveryRace("rotated", "session-a")

    @Test
    fun `a delayed failed read cannot erase a new session with identical ciphertext`() = recoveryRace("original", "session-b")

    private fun recoveryRace(newToken: String, newOwner: String) = runTest {
        val dataStore = dataStore()
        val writer = AuthStore(dataStore, XorCipher(), StandardTestDispatcher(testScheduler))
        writer.saveRefreshToken("original", "session-a")
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var firstRead = true
        val cipher = object : SecretCipher by XorCipher() {
            override fun decrypt(data: ByteArray): ByteArray {
                if (firstRead) {
                    firstRead = false
                    started.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS)) { "Test did not release decryption" }
                    throw GeneralSecurityException("old token unreadable")
                }
                return XorCipher().decrypt(data)
            }
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val reader = AuthStore(dataStore, cipher, io)
            val reading = async { reader.stored.first() }
            try {
                started.await()
                writer.saveRefreshToken(newToken, newOwner)
            } finally {
                release.countDown()
            }

            assertEquals(StoredAuth(newToken, null), reading.await())
        }
        assertEquals(newOwner, writer.cleanupOwner())
        assertNull(writer.stored.first().pendingCleanup)
    }

    @Test
    fun `a truncated encrypted session is rejected before accessing the Keystore`() {
        val failure = runCatching { KeystoreCipher().decrypt(ByteArray(12)) }.exceptionOrNull()
        assertTrue(failure is GeneralSecurityException)
        assertFalse(failure!!.message.orEmpty().contains("AndroidKeyStore"))
    }
}
