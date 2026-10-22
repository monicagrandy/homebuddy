package chat.homebuddy.core.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first

/** Where the serialized OAuth state (including the refresh token) lives between launches. */
interface AuthStateStore {
    suspend fun read(): String?
    suspend fun write(value: String)
    suspend fun clear()
}

interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/**
 * DataStore holding the state encrypted with a key that never leaves the Android Keystore
 * (EncryptedSharedPreferences is deprecated). If the key is gone, for example after a restore
 * to a new phone, the state can't be read and the user simply signs in again.
 */
class EncryptedAuthStateStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : AuthStateStore {
    override suspend fun read(): String? {
        val stored = dataStore.data.first()[KEY] ?: return null
        return runCatching { cipher.decrypt(Base64.getDecoder().decode(stored)).decodeToString() }
            .getOrElse {
                clear()
                null
            }
    }

    override suspend fun write(value: String) {
        val sealed = Base64.getEncoder().encodeToString(cipher.encrypt(value.encodeToByteArray()))
        dataStore.edit { it[KEY] = sealed }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("auth_state")
    }
}

/** AES-256-GCM with a non-exportable Android Keystore key. Output is the 12-byte IV followed by the ciphertext. */
class KeystoreCipher(private val alias: String = "homebuddy_auth_state") : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_BYTES))
        }
        return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
