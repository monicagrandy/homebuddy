package chat.homebuddy.core.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Why this phone's session ended when the user didn't end it; the sign-in screen says so until the next sign-in attempt. */
enum class SignOutReason { SESSION_ENDED, ACCOUNT_DELETED, NOT_IN_BETA }

/** Credentials, the last sign-out reason, and cleanup ownership that survives credential removal. */
data class StoredAuth(val refreshToken: String?, val signOutReason: SignOutReason?, val pendingCleanup: String? = null)

interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/**
 * The session between launches, and the only record of being signed in: a stored refresh token
 * means signed in. The token is encrypted with a Keystore key; if that key is gone (for example
 * after a backup is restored to a new phone) it can't be read. The unusable credentials are
 * removed and durable cleanup is scheduled before the next sign-in.
 *
 * Access and ID tokens are never stored. They live in memory ([AccessTokenCache]).
 */
class AuthStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val stored: Flow<StoredAuth> = dataStore.data
        .map { read(it) }
        .distinctUntilChanged()

    suspend fun saveRefreshToken(token: String, sessionId: String? = null) {
        dataStore.edit {
            if (it[CLEANUP_SESSION_ID] != null) throw IOException("Session cleanup must finish before signing in.")
            // DataStore serializes key creation and credential writes, even for concurrent callers.
            val sealed = withContext(io) {
                try {
                    Base64.getEncoder().encodeToString(cipher.encrypt(token.encodeToByteArray()))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    throw IOException("Couldn't securely save the session.", failure)
                }
            }
            it[REFRESH_TOKEN] = sealed
            if (sessionId != null) it[SESSION_ID] = sessionId
            else if (it[SESSION_ID] == null) it[SESSION_ID] = UUID.randomUUID().toString()
            it.remove(SIGN_OUT_REASON)
        }
    }

    suspend fun clear(reason: SignOutReason?) {
        dataStore.edit {
            it.remove(REFRESH_TOKEN)
            if (it[CLEANUP_SESSION_ID] == null) {
                it.remove(SESSION_ID)
                if (reason != null) it[SIGN_OUT_REASON] = reason.name else it.remove(SIGN_OUT_REASON)
            }
        }
    }

    /** A unique sign-in, including a cleanup whose credentials have already been removed. */
    suspend fun cleanupOwner(): String? {
        // Lazily give sessions saved before session IDs were introduced an identity too.
        val preferences = dataStore.edit {
            if (it[REFRESH_TOKEN] != null && it[SESSION_ID] == null) it[SESSION_ID] = UUID.randomUUID().toString()
        }
        return preferences[CLEANUP_SESSION_ID] ?: preferences[SESSION_ID].takeIf { preferences[REFRESH_TOKEN] != null }
    }

    /** Atomically records cleanup ownership, the first reason, and the loss of credentials. */
    suspend fun beginCleanup(owner: String, reason: SignOutReason?): Boolean {
        val preferences = dataStore.edit {
            if (it[CLEANUP_SESSION_ID] == null && it[SESSION_ID] == owner && it[REFRESH_TOKEN] != null) {
                it.remove(REFRESH_TOKEN)
                it[CLEANUP_SESSION_ID] = owner
                if (reason != null) it[SIGN_OUT_REASON] = reason.name else it.remove(SIGN_OUT_REASON)
            }
        }
        return preferences[CLEANUP_SESSION_ID] == owner
    }

    suspend fun completeCleanup(owner: String) {
        dataStore.edit {
            if (it[CLEANUP_SESSION_ID] == owner) {
                it.remove(CLEANUP_SESSION_ID)
                it.remove(SESSION_ID)
            }
        }
    }

    suspend fun clearSignOutReason() {
        dataStore.edit { if (it[CLEANUP_SESSION_ID] == null) it.remove(SIGN_OUT_REASON) }
    }

    private suspend fun read(initial: Preferences): StoredAuth {
        var preferences = initial
        while (true) {
            val sealed = preferences[REFRESH_TOKEN]
            val token = try {
                sealed?.let { withContext(io) { cipher.decrypt(Base64.getDecoder().decode(it)).decodeToString() } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val failed = preferences
                preferences = dataStore.edit {
                    // A slow read must not erase credentials saved by a newer sign-in or rotation.
                    if (it[REFRESH_TOKEN] == sealed && it[SESSION_ID] == failed[SESSION_ID]) {
                        it.remove(REFRESH_TOKEN)
                        if (it[CLEANUP_SESSION_ID] == null) {
                            val owner = it[SESSION_ID] ?: UUID.randomUUID().toString()
                            it[SESSION_ID] = owner
                            it[CLEANUP_SESSION_ID] = owner
                            it[SIGN_OUT_REASON] = SignOutReason.SESSION_ENDED.name
                        }
                    }
                }
                // Re-read the transaction's result, including a newer session if it won the race.
                continue
            }
            return StoredAuth(
                refreshToken = token,
                signOutReason = preferences[SIGN_OUT_REASON]?.let { raw -> SignOutReason.entries.firstOrNull { it.name == raw } },
                pendingCleanup = preferences[CLEANUP_SESSION_ID],
            )
        }
    }

    private companion object {
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val SIGN_OUT_REASON = stringPreferencesKey("sign_out_reason")
        val SESSION_ID = stringPreferencesKey("session_id")
        val CLEANUP_SESSION_ID = stringPreferencesKey("cleanup_session_id")
    }
}

/** AES-256-GCM with a non-exportable Android Keystore key. Output is the 12-byte IV followed by the ciphertext. */
class KeystoreCipher(private val alias: String = "homebuddy_auth") : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray = try {
        encryptOnce(plain)
    } catch (failure: GeneralSecurityException) {
        if (failure !is KeyPermanentlyInvalidatedException && failure !is UnrecoverableKeyException) throw failure
        // A permanently lost key cannot protect a new sign-in. Replace it once, never on a read
        // or a transient Keystore error, and let AuthStore surface any retry failure as IOException.
        KeyStore.getInstance(KEYSTORE).apply { load(null); deleteEntry(alias) }
        encryptOnce(plain)
    }

    private fun encryptOnce(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(create = true)) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        if (data.size < IV_BYTES + TAG_BYTES) throw GeneralSecurityException("Invalid encrypted session.")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            val key = key(create = false) ?: throw UnrecoverableKeyException("The session key is missing.")
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, IV_BYTES))
        }
        return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    private fun key(create: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
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
        const val TAG_BYTES = 16
    }
}
