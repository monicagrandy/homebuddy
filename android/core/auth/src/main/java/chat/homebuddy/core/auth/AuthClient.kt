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

data class AuthTokens(val accessToken: String, val idToken: String?)

class SignInCancelled : Exception("Sign-in was cancelled.")

class SignInFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The OAuth side of signing in (Cognito hosted UI, auth code + PKCE). Kept behind an interface
 * so the AppAuth library, last released in 2021, can be replaced without touching the rest.
 */
interface AuthClient {
    /** Opens the hosted sign-in page in a Custom Tab. */
    fun signInIntent(): Intent

    /** Finishes sign-in with the result the Custom Tab returned. Throws [SignInCancelled] or [SignInFailed]. */
    suspend fun completeSignIn(result: Intent?): AuthTokens

    /**
     * Current tokens, refreshed if needed. Null when there's no session or the refresh token was
     * rejected. Throws [IOException] when Cognito can't be reached.
     */
    suspend fun tokens(forceRefresh: Boolean = false): AuthTokens?

    suspend fun hasSession(): Boolean

    /** Forgets the tokens on this device. */
    suspend fun clear()

    /** The hosted UI's logout page, which ends its own session so the next sign-in can pick another account. */
    fun signOutUrl(): String
}
