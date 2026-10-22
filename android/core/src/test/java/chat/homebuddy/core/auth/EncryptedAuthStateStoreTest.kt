package chat.homebuddy.core.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Stand-in for the Keystore cipher, which only exists on a device. */
private class XorCipher(private val broken: Boolean = false) : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun decrypt(data: ByteArray): ByteArray {
        check(!broken) { "key is gone" }
        return encrypt(data)
    }
}

class EncryptedAuthStateStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.dataStore() =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "auth.preferences_pb") }

    @Test
    fun `state round-trips and is never stored as plain text`() = runTest {
        val dataStore = dataStore()
        val store = EncryptedAuthStateStore(dataStore, XorCipher())

        store.write("""{"refreshToken":"secret-refresh"}""")

        assertEquals("""{"refreshToken":"secret-refresh"}""", store.read())
        val raw = dataStore.data.first()[stringPreferencesKey("auth_state")].orEmpty()
        assertFalse(raw.contains("secret-refresh"))
    }

    @Test
    fun `clearing forgets the state`() = runTest {
        val store = EncryptedAuthStateStore(dataStore(), XorCipher())
        store.write("state")

        store.clear()

        assertNull(store.read())
    }

    @Test
    fun `state that can no longer be decrypted is dropped`() = runTest {
        val dataStore = dataStore()
        EncryptedAuthStateStore(dataStore, XorCipher()).write("state")
        val afterRestore = EncryptedAuthStateStore(dataStore, XorCipher(broken = true))

        assertNull(afterRestore.read())
        assertNull(dataStore.data.first()[stringPreferencesKey("auth_state")])
    }
}
