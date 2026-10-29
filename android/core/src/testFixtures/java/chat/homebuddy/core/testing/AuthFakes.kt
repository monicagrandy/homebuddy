package chat.homebuddy.core.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import chat.homebuddy.core.auth.AccessTokenCache
import chat.homebuddy.core.auth.AuthClient
import chat.homebuddy.core.auth.AuthRepository
import chat.homebuddy.core.auth.AuthStore
import chat.homebuddy.core.auth.SecretCipher
import chat.homebuddy.core.auth.SignInPageResult
import chat.homebuddy.core.auth.TokenSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stand-in for Cognito. Each test sets what the code exchange and refresh return. */
class FakeAuthClient : AuthClient {
    var exchange: suspend () -> TokenSet = { TokenSet("access-1", "id-1", "refresh-1", expiresAtMillis = null) }
    var refresh: suspend (String) -> TokenSet? = { TokenSet("access-2", "id-2", refreshToken = null, expiresAtMillis = null) }

    override suspend fun exchange(code: SignInPageResult.Code) = exchange.invoke()
    override suspend fun refresh(refreshToken: String) = refresh.invoke(refreshToken)
    override fun signOutUrl() = "https://auth.example.com/logout".also { logoutPageRequests++ }

    /** How many times the logout page's address was asked for. */
    var logoutPageRequests = 0
        private set
}

/** Stand-in for the Keystore cipher, which only exists on a device. */
class XorCipher(private val broken: Boolean = false) : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun decrypt(data: ByteArray): ByteArray {
        check(!broken) { "key is gone" }
        return encrypt(data)
    }
}

/** The real [AuthRepository] on [client], keeping its session in memory instead of an encrypted file. */
fun testAuthRepository(client: AuthClient = FakeAuthClient(), cache: AccessTokenCache = AccessTokenCache()): AuthRepository =
    // The fake cipher does no IO; keep fixture operations on the test's coroutine scheduler.
    AuthRepository(client, AuthStore(InMemoryPreferences(), XorCipher(), Dispatchers.Unconfined), cache)

private class InMemoryPreferences : DataStore<Preferences> {
    private val current = MutableStateFlow(emptyPreferences())
    private val writing = Mutex()

    override val data: Flow<Preferences> = current

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        writing.withLock { transform(current.value).also { current.value = it } }
}
