package chat.homebuddy.core.auth

import android.content.Intent
import java.io.IOException

data class AuthConfig(
    /** The Cognito hosted UI, e.g. https://homebuddy.auth.us-east-2.amazoncognito.com */
    val cognitoDomain: String,
    /** The public (no secret) Android app client. */
    val clientId: String,
    val redirectUri: String = "homebuddy://auth/callback",
    val signOutUri: String = "homebuddy://auth/signout",
    val scopes: List<String> = listOf("openid", "email", "profile"),
)

/** What a sign-in or refresh returns. Only [refreshToken] is ever written to disk. */
data class TokenSet(
    val accessToken: String,
    val idToken: String?,
    val refreshToken: String?,
    /** When [accessToken] expires (epoch millis), if the server said. */
    val expiresAtMillis: Long?,
)

class SignInCancelled : Exception("Sign-in was cancelled.")

class SignInFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The OAuth calls (Cognito hosted UI, auth code + PKCE). It holds no state, and sits behind an
 * interface so the AppAuth library, last released in 2021, can be swapped without touching the rest.
 */
interface AuthClient {
    /** Opens the hosted sign-in page in a Custom Tab. */
    fun signInIntent(): Intent

    /** Exchanges the code the Custom Tab returned for tokens. Throws [SignInCancelled], [SignInFailed] or [IOException]. */
    suspend fun exchange(result: Intent?): TokenSet

    /**
     * New tokens for [refreshToken]; their refresh token is null unless the server rotated it.
     * Returns null when [refreshToken] was rejected (expired, revoked, account deleted). Throws [IOException] offline.
     */
    suspend fun refresh(refreshToken: String): TokenSet?

    /** The hosted UI's logout page, which ends its own session so the next sign-in can pick another account. */
    fun signOutUrl(): String
}
