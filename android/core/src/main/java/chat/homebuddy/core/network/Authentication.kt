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
    /** An atomic session/token snapshot, refreshed if needed. Throws [IOException] offline. */
    suspend fun credentials(): RequestCredentials

    /** Reuses a newer credential or refreshes [rejected]. Null if its session was superseded. */
    suspend fun refresh(rejected: RequestCredentials): RequestCredentials?

    /**
     * The API rejected the session even after a refresh, e.g. the account was deleted on another
     * device. Only acts if [rejected] still identifies the current session and credential.
     * Called on an OkHttp thread, which waits for it to finish.
     */
    suspend fun onSessionRejected(rejected: RequestCredentials)
}

/** Local request metadata; the session ID and revision are never sent to the server. */
data class RequestCredentials(val sessionId: String, val accessToken: String?, val revision: Long)

private const val AUTHORIZATION = "Authorization"
private data class RequestAuthentication(val credentials: RequestCredentials, val retried: Boolean)

/** Adds `Authorization: Bearer` with a fresh token. Runs on OkHttp's threads, so blocking is fine here. */
class AuthInterceptor(private val tokens: Provider<AccessTokenProvider>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(AUTHORIZATION) != null) return chain.proceed(request)
        val credentials = runBlocking { tokens.get().credentials() }
        return chain.proceed(request.withCredentials(credentials, retried = false))
    }
}

/** Retries once within the request's session; only rejection of its current credential ends that session. */
class TokenAuthenticator(private val tokens: Provider<AccessTokenProvider>) : Authenticator {
    // OkHttp calls this synchronously on its own thread, so blocking until the suspend calls finish is fine here.
    override fun authenticate(route: Route?, response: Response): Request? {
        val attempt = response.request.tag(RequestAuthentication::class.java) ?: return null
        val rejected = attempt.credentials
        // Caller-supplied credentials and cross-origin redirects do not belong to this provider.
        if (response.request.header(AUTHORIZATION) != rejected.accessToken?.let { "Bearer $it" }) return null
        val provider = tokens.get()
        if (rejected.accessToken == null || attempt.retried) {
            runBlocking { provider.onSessionRejected(rejected) }
            return null
        }
        val fresh = runBlocking { provider.refresh(rejected) } ?: return null
        if (fresh.sessionId != rejected.sessionId) return null
        if (fresh.accessToken == null || fresh.accessToken == rejected.accessToken) {
            runBlocking { provider.onSessionRejected(fresh) }
            return null
        }
        return response.request.withCredentials(fresh, retried = true)
    }
}

/** Identifies the app and its version (`X-Client: android/1.0.0`) in server logs. */
class ClientHeaderInterceptor(private val clientHeader: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("X-Client", clientHeader).build())
}

private fun Request.withCredentials(credentials: RequestCredentials, retried: Boolean): Request = newBuilder()
    .tag(RequestAuthentication::class.java, RequestAuthentication(credentials, retried))
    .apply {
        if (credentials.accessToken != null) header(AUTHORIZATION, "Bearer ${credentials.accessToken}")
        else removeHeader(AUTHORIZATION)
    }
    .build()
