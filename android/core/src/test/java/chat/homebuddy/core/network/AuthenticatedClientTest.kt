package chat.homebuddy.core.network

import java.io.IOException
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class FakeTokens : AccessTokenProvider {
    var current: String? = "token-1"
    var afterRefresh: String? = "token-2"
    var refreshError: IOException? = null
    var refreshes = 0
    var rejections = 0

    override suspend fun accessToken(forceRefresh: Boolean): String? {
        if (forceRefresh) {
            refreshes++
            refreshError?.let { throw it }
            current = afterRefresh
        }
        return current
    }

    override suspend fun onSessionRejected() {
        rejections++
    }
}

class AuthenticatedClientTest {
    private val server = MockWebServer()
    private val tokens = FakeTokens()
    private lateinit var api: HomeBuddyApi

    private val households = """[{"id": 1, "name": "Elm Street", "zip_code": "80202", "home_type": "condo", "role": "owner"}]"""

    @Before
    fun setUp() {
        server.start()
        val config = ApiConfig(baseUrl = server.url("/").toString(), clientHeader = "android/9.9.9")
        api = buildApi(config, buildHttpClient(config, Provider { tokens }))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun unauthorized() = MockResponse().setResponseCode(401).setBody("""{"detail": "JWT decode failed."}""")

    @Test
    fun `requests carry the access token and the app version`() = runTest {
        server.enqueue(ok(households))

        api.households()

        val request = server.takeRequest()
        assertEquals("Bearer token-1", request.getHeader("Authorization"))
        assertEquals("android/9.9.9", request.getHeader("X-Client"))
    }

    @Test
    fun `a 401 refreshes the token once and retries`() = runTest {
        server.enqueue(unauthorized())
        server.enqueue(ok(households))

        val result = api.households()

        assertEquals(1, result.size)
        assertEquals("Bearer token-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer token-2", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, tokens.refreshes)
        assertEquals(0, tokens.rejections)
    }

    @Test
    fun `a second 401 after refreshing ends the session`() = runTest {
        server.enqueue(unauthorized())
        server.enqueue(unauthorized())

        val error = runCatching { api.households() }.exceptionOrNull()

        assertEquals(401, (error as HttpException).code())
        assertEquals(2, server.requestCount)
        assertEquals(1, tokens.rejections)
    }

    @Test
    fun `a rejected refresh token ends the session without retrying`() = runTest {
        tokens.afterRefresh = null
        server.enqueue(unauthorized())

        val error = runCatching { api.households() }.exceptionOrNull()

        assertEquals(401, (error as HttpException).code())
        assertEquals(1, server.requestCount)
        assertEquals(1, tokens.rejections)
    }

    @Test
    fun `being offline during a refresh doesn't sign anyone out`() = runTest {
        tokens.refreshError = IOException("no network")
        server.enqueue(unauthorized())

        try {
            api.households()
            fail("expected an IOException")
        } catch (expected: IOException) {
            // The call fails like any network error and can be retried later.
        }
        assertEquals(0, tokens.rejections)
    }

    @Test
    fun `without a session no Authorization header is sent`() = runTest {
        tokens.current = null
        server.enqueue(ok(households))

        api.households()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a 401 on a request sent without a token ends the session`() = runTest {
        tokens.current = null
        server.enqueue(unauthorized())

        val error = runCatching { api.households() }.exceptionOrNull()

        assertEquals(401, (error as HttpException).code())
        assertEquals(1, server.requestCount)
        assertEquals(1, tokens.rejections)
    }

    @Test
    fun `bootstrap sends the tokens the way the backend names them`() = runTest {
        server.enqueue(ok("""{"id": 1, "cognito_sub": "s", "email": "pat@example.com", "display_name": null, "added_later": true}"""))

        val user = api.bootstrap(BootstrapRequest(idToken = "id-1", accessToken = "access-1"))

        assertEquals("""{"id_token":"id-1","access_token":"access-1"}""", server.takeRequest().body.readUtf8())
        assertNull(user.displayName)
    }

    @Test
    fun `server errors surface as HttpException with a readable message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1").setBody("""{"detail": "HomeBuddy is busy."}"""))

        val error = assertThrows(HttpException::class.java) { kotlinx.coroutines.runBlocking { api.households() } }

        assertEquals(ApiError(503, null, "HomeBuddy is busy.", retryAfterSeconds = 1), error.toApiError())
    }
}
