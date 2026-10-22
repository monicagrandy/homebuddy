package chat.homebuddy.core.network

import java.io.IOException
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Supplies access tokens to the HTTP client. Implemented by NetworkSession in core.domain. */
interface AccessTokenProvider {
    /**
     * A valid access token, refreshed first when it's about to expire (or when [forceRefresh]).
     * Returns null when there is no usable session. Throws [IOException] when sign-in can't be
     * reached, so being offline never signs anyone out.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String?

    /**
     * The API rejected the session even after a refresh, e.g. the account was deleted on another
     * device. Called on an OkHttp thread, which waits for it to finish.
     */
    suspend fun onSessionRejected()
}

private const val AUTHORIZATION = "Authorization"

/** Adds `Authorization: Bearer` with a fresh token. Runs on OkHttp's threads, so blocking is fine here. */
class AuthInterceptor(private val tokens: Provider<AccessTokenProvider>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(AUTHORIZATION) != null) return chain.proceed(request)
        val token = runBlocking { tokens.get().accessToken() } ?: return chain.proceed(request)
        return chain.proceed(request.withBearer(token))
    }
}

/** On a 401, refreshes once and retries once. A second 401 (or one without a usable token) ends the session. */
class TokenAuthenticator(private val tokens: Provider<AccessTokenProvider>) : Authenticator {
    // OkHttp calls this synchronously on its own thread, so blocking until the suspend calls finish is fine here.
    override fun authenticate(route: Route?, response: Response): Request? {
        val sent = response.request.header(AUTHORIZATION)?.removePrefix("Bearer ")
        if (sent == null || response.priorResponse != null) {
            runBlocking { tokens.get().onSessionRejected() }
            return null
        }
        val fresh = runBlocking { tokens.get().accessToken(forceRefresh = true) }
        if (fresh == null || fresh == sent) {
            runBlocking { tokens.get().onSessionRejected() }
            return null
        }
        return response.request.withBearer(fresh)
    }
}

/** Identifies the app and its version (`X-Client: android/1.0.0`) in server logs. */
class ClientHeaderInterceptor(private val clientHeader: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("X-Client", clientHeader).build())
}

private fun Request.withBearer(token: String): Request =
    newBuilder().header(AUTHORIZATION, "Bearer $token").build()
