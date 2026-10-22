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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Why this phone's session ended when the user didn't end it; the sign-in screen says so until the next sign-in attempt. */
enum class SignOutReason { SESSION_ENDED, ACCOUNT_DELETED, NOT_IN_BETA }

/** What's on disk: a refresh token while signed in, otherwise why the last session ended. */
data class StoredAuth(val refreshToken: String?, val signOutReason: SignOutReason?)

interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/**
 * The session between launches, and the only record of being signed in: a stored refresh token
 * means signed in. The token is encrypted with a Keystore key; if that key is gone (for example
 * after a backup is restored to a new phone) it can't be read, which counts as signed out.
 *
 * Access and ID tokens are never stored. They live in memory ([AccessTokenCache]).
 */
class AuthStore(private val dataStore: DataStore<Preferences>, private val cipher: SecretCipher) {
    val stored: Flow<StoredAuth> = dataStore.data
        .map { preferences ->
            StoredAuth(
                refreshToken = preferences[REFRESH_TOKEN]?.let(::open),
                signOutReason = preferences[SIGN_OUT_REASON]?.let { raw -> SignOutReason.entries.firstOrNull { it.name == raw } },
            )
        }
        .distinctUntilChanged()

    suspend fun saveRefreshToken(token: String) {
        val sealed = Base64.getEncoder().encodeToString(cipher.encrypt(token.encodeToByteArray()))
        dataStore.edit {
            it[REFRESH_TOKEN] = sealed
            it.remove(SIGN_OUT_REASON)
        }
    }

    suspend fun clear(reason: SignOutReason?) {
        dataStore.edit {
            it.remove(REFRESH_TOKEN)
            if (reason != null) it[SIGN_OUT_REASON] = reason.name else it.remove(SIGN_OUT_REASON)
        }
    }

    suspend fun clearSignOutReason() {
        dataStore.edit { it.remove(SIGN_OUT_REASON) }
    }

    private fun open(sealed: String): String? =
        runCatching { cipher.decrypt(Base64.getDecoder().decode(sealed)).decodeToString() }.getOrNull()

    private companion object {
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val SIGN_OUT_REASON = stringPreferencesKey("sign_out_reason")
    }
}

/** AES-256-GCM with a non-exportable Android Keystore key. Output is the 12-byte IV followed by the ciphertext. */
class KeystoreCipher(private val alias: String = "homebuddy_auth") : SecretCipher {
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
