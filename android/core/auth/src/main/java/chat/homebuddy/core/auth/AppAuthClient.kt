package chat.homebuddy.core.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse

/** [AuthClient] on AppAuth. PKCE is added by AppAuth automatically. */
class AppAuthClient(
    context: Context,
    private val config: AuthConfig,
    private val store: AuthStateStore,
) : AuthClient {
    private val service by lazy { AuthorizationService(context.applicationContext) }
    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("${config.cognitoDomain.trimEnd('/')}/oauth2/authorize"),
        Uri.parse("${config.cognitoDomain.trimEnd('/')}/oauth2/token"),
    )
    // AuthState isn't thread-safe, and two requests refreshing at once would race on the refresh token.
    private val mutex = Mutex()
    private var cached: AuthState? = null

    override fun signInIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            config.clientId,
            ResponseTypeValues.CODE,
            Uri.parse(config.redirectUri),
        ).setScopes(config.scopes).build()
        return service.getAuthorizationRequestIntent(request)
    }

    override suspend fun completeSignIn(result: Intent?): AuthTokens = mutex.withLock {
        val data = result ?: throw SignInCancelled()
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        if (response == null) {
            if (error?.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code) throw SignInCancelled()
            throw SignInFailed(error?.errorDescription ?: "Sign-in failed.", error)
        }
        val state = AuthState(response, error)
        val tokenResponse = suspendCancellableCoroutine<TokenResponse> { continuation ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { tokens, exception ->
                if (tokens != null) {
                    continuation.resume(tokens)
                } else {
                    continuation.resumeWithException(SignInFailed(exception?.errorDescription ?: "Sign-in failed.", exception))
                }
            }
        }
        state.update(tokenResponse, null)
        save(state)
        AuthTokens(accessToken = checkNotNull(state.accessToken), idToken = state.idToken)
    }

    override suspend fun tokens(forceRefresh: Boolean): AuthTokens? = mutex.withLock {
        val state = load()
        if (!state.isAuthorized) return@withLock null
        if (forceRefresh) state.needsTokenRefresh = true
        val tokens = suspendCancellableCoroutine<AuthTokens?> { continuation ->
            state.performActionWithFreshTokens(service) { accessToken, idToken, exception ->
                when {
                    exception == null && accessToken != null -> continuation.resume(AuthTokens(accessToken, idToken))
                    exception?.type == AuthorizationException.TYPE_GENERAL_ERROR &&
                        exception.code == AuthorizationException.GeneralErrors.NETWORK_ERROR.code ->
                        continuation.resumeWithException(IOException("Couldn't reach sign-in.", exception))
                    // Refresh token rejected (expired, revoked, account deleted): the session is over.
                    else -> continuation.resume(null)
                }
            }
        }
        save(state) // Keeps a rotated refresh token.
        tokens
    }

    override suspend fun hasSession(): Boolean = mutex.withLock { load().isAuthorized }

    override suspend fun clear() = mutex.withLock {
        cached = AuthState(serviceConfig)
        store.clear()
    }

    override fun signOutUrl(): String =
        Uri.parse("${config.cognitoDomain.trimEnd('/')}/logout").buildUpon()
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("logout_uri", config.signOutUri)
            .build()
            .toString()

    private suspend fun load(): AuthState =
        cached ?: (store.read()?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() } ?: AuthState(serviceConfig))
            .also { cached = it }

    private suspend fun save(state: AuthState) {
        cached = state
        store.write(state.jsonSerializeString())
    }
}
