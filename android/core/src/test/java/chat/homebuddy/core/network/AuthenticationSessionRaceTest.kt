package chat.homebuddy.core.network

import chat.homebuddy.core.auth.SignInPageResult
import chat.homebuddy.core.auth.TokenSet
import chat.homebuddy.core.domain.EndSessionUseCase
import chat.homebuddy.core.domain.NetworkSession
import chat.homebuddy.core.domain.SignInResult
import chat.homebuddy.core.domain.SignInUseCase
import chat.homebuddy.core.testing.FakeAuthClient
import chat.homebuddy.core.testing.TestConversationsRepository
import chat.homebuddy.core.testing.TestDocumentsRepository
import chat.homebuddy.core.testing.TestProfileRepository
import chat.homebuddy.core.testing.testAuthRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

/** Real interceptors, token cache and session cleanup, with HTTP responses released in test order. */
class AuthenticationSessionRaceTest {
    private val server = MockWebServer()
    private val client = FakeAuthClient()
    private val auth = testAuthRepository(client)
    private val profile = TestProfileRepository()
    private val conversations = TestConversationsRepository()
    private val end = EndSessionUseCase(auth, profile, TestDocumentsRepository(), conversations)
    private val signIn = SignInUseCase(auth, profile, end)
    private val provider = NetworkSession(auth, end)
    private lateinit var api: HomeBuddyApi
    private val code = SignInPageResult.Code("authorization-response")

    @Before
    fun setUp() {
        server.start()
        val config = ApiConfig(server.url("/").toString(), "android/test")
        api = buildApi(config, buildHttpClient(config, Provider { provider }))
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `a delayed first 401 cannot retry or sign out another account`() = runTest {
        assertEquals(SignInResult.SignedIn, signIn(code))
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = delayed401(arrived, release)
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { api.households() } }
        try {
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            end()
            client.exchange = { TokenSet("other-access", "other-id", "other-refresh", null) }
            assertEquals(SignInResult.SignedIn, signIn(code))
        } finally {
            release.countDown()
        }

        assertEquals(401, (result.await().exceptionOrNull() as HttpException).code())
        assertTrue(auth.isSignedIn())
        assertEquals("other-access", auth.accessToken())
        assertEquals(1, server.requestCount)
        assertEquals(1, conversations.wipes)
    }

    @Test
    fun `an unauthenticated request cannot sign out an account that signs in before its 401`() = runTest {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = delayed401(arrived, release)
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { api.households() } }
        try {
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            assertEquals(SignInResult.SignedIn, signIn(code))
        } finally {
            release.countDown()
        }

        assertEquals(401, (result.await().exceptionOrNull() as HttpException).code())
        assertTrue(auth.isSignedIn())
        assertEquals(0, conversations.wipes)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a delayed second 401 cannot reject a token refreshed by another request`() = runTest {
        assertEquals(SignInResult.SignedIn, signIn(code))
        val refreshes = AtomicInteger()
        client.refresh = { TokenSet("renewed-${refreshes.incrementAndGet()}", null, null, null) }
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (requests.incrementAndGet() == 2) {
                    arrived.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return MockResponse().setResponseCode(401)
            }
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { api.households() } }
        try {
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            assertEquals("renewed-2", auth.accessToken(forceRefresh = true))
        } finally {
            release.countDown()
        }

        assertEquals(401, (result.await().exceptionOrNull() as HttpException).code())
        assertTrue(auth.isSignedIn())
        assertEquals("renewed-2", auth.accessToken())
        assertEquals(0, conversations.wipes)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `parallel HTTP 401 responses share one refresh and retry with the same credential`() = runTest {
        assertEquals(SignInResult.SignedIn, signIn(code))
        val refreshes = AtomicInteger()
        client.refresh = {
            refreshes.incrementAndGet()
            TokenSet("renewed", null, null, null)
        }
        val originals = CountDownLatch(2)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") == "Bearer access-1") {
                    originals.countDown()
                    check(originals.await(5, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(401)
                }
                assertEquals("Bearer renewed", request.getHeader("Authorization"))
                return MockResponse().setBody("[]")
            }
        }

        val first = async(start = CoroutineStart.UNDISPATCHED) { api.households() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { api.households() }

        assertTrue(first.await().isEmpty())
        assertTrue(second.await().isEmpty())
        assertEquals(1, refreshes.get())
        assertEquals(4, server.requestCount)
        assertTrue(auth.isSignedIn())
    }

    private fun delayed401(arrived: CountDownLatch, release: CountDownLatch) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            arrived.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            return MockResponse().setResponseCode(401)
        }
    }
}
