package chat.homebuddy.core.auth

import android.content.Intent
import android.net.Uri
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse

/** [AuthClient] on AppAuth. PKCE is added by AppAuth automatically. */
class AppAuthClient(
    /** From the Hilt graph, built on the application context (see AuthModule), so no Activity can leak. */
    private val service: AuthorizationService,
    private val config: AuthConfig,
) : AuthClient {
    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("${config.cognitoDomain.trimEnd('/')}/oauth2/authorize"),
        Uri.parse("${config.cognitoDomain.trimEnd('/')}/oauth2/token"),
    )

    override fun signInIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            config.clientId,
            ResponseTypeValues.CODE,
            Uri.parse(config.redirectUri),
        ).setScopes(config.scopes).build()
        return service.getAuthorizationRequestIntent(request)
    }

    override suspend fun exchange(result: Intent?): TokenSet {
        val data = result ?: throw SignInCancelled()
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        if (response == null) {
            if (error?.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code) throw SignInCancelled()
            throw SignInFailed(error?.errorDescription ?: "Sign-in failed.", error)
        }
        val tokens = try {
            request(response.createTokenExchangeRequest())
        } catch (failure: AuthorizationException) {
            if (failure.isNetworkError()) throw IOException("Couldn't reach sign-in.", failure)
            throw SignInFailed(failure.errorDescription ?: "Sign-in failed.", failure)
        }
        return tokens.toTokenSet() ?: throw SignInFailed("Sign-in returned no access token.")
    }

    override suspend fun refresh(refreshToken: String): TokenSet? {
        val request = TokenRequest.Builder(serviceConfig, config.clientId)
            .setGrantType(GrantTypeValues.REFRESH_TOKEN)
            .setRefreshToken(refreshToken)
            .build()
        val renewed = try {
            request(request)
        } catch (failure: AuthorizationException) {
            // Cognito said no (invalid_grant: the token expired or was revoked, or the account is gone).
            if (failure.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR) return null
            // Offline, a server error or a response that didn't validate: keep the session and retry later.
            // Only IOException may leave an OkHttp interceptor, so nothing else is thrown here.
            throw IOException("Couldn't refresh the sign-in.", failure)
        }
        return renewed.toTokenSet() ?: throw IOException("The refresh returned no access token.")
    }

    override fun signOutUrl(): String =
        Uri.parse("${config.cognitoDomain.trimEnd('/')}/logout").buildUpon()
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("logout_uri", config.signOutUri)
            .build()
            .toString()

    private suspend fun request(request: TokenRequest): TokenResponse = suspendCancellableCoroutine { continuation ->
        service.performTokenRequest(request) { response: TokenResponse?, failure: AuthorizationException? ->
            if (response != null) {
                continuation.resume(response)
            } else {
                continuation.resumeWithException(failure ?: AuthorizationException.GeneralErrors.SERVER_ERROR)
            }
        }
    }
}

private fun AuthorizationException.isNetworkError(): Boolean =
    type == AuthorizationException.TYPE_GENERAL_ERROR && code == AuthorizationException.GeneralErrors.NETWORK_ERROR.code

private fun TokenResponse.toTokenSet(): TokenSet? = accessToken?.let { access ->
    TokenSet(accessToken = access, idToken = idToken, refreshToken = refreshToken, expiresAtMillis = accessTokenExpirationTime)
}
