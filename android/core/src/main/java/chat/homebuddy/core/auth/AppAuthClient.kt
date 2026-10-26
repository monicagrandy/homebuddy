package chat.homebuddy.core.auth

import android.net.Uri
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.json.JSONException

/** [AuthClient] on AppAuth. PKCE is added by AppAuth automatically. */
class AppAuthClient(
    /** From the Hilt graph, built on the application context (see AuthModule), so no Activity can leak. */
    private val service: AuthorizationService,
    private val config: AuthConfig,
) : AuthClient {
    private val serviceConfig = config.serviceConfiguration()

    override suspend fun exchange(code: SignInPageResult.Code): TokenSet {
        val response = try {
            AuthorizationResponse.jsonDeserialize(code.response)
        } catch (failure: JSONException) {
            throw SignInFailed("Sign-in returned an unreadable response.", failure)
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

/** Cognito's authorize and token endpoints, for [AppAuthClient] and [SignInPage]. */
internal fun AuthConfig.serviceConfiguration() = AuthorizationServiceConfiguration(
    Uri.parse("${cognitoDomain.trimEnd('/')}/oauth2/authorize"),
    Uri.parse("${cognitoDomain.trimEnd('/')}/oauth2/token"),
)

private fun AuthorizationException.isNetworkError(): Boolean =
    type == AuthorizationException.TYPE_GENERAL_ERROR && code == AuthorizationException.GeneralErrors.NETWORK_ERROR.code

private fun TokenResponse.toTokenSet(): TokenSet? = accessToken?.let { access ->
    TokenSet(accessToken = access, idToken = idToken, refreshToken = refreshToken, expiresAtMillis = accessTokenExpirationTime)
}
