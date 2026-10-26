package chat.homebuddy.core.data.profile

import chat.homebuddy.core.data.apiFor
import chat.homebuddy.core.data.json
import chat.homebuddy.core.network.HouseholdDto
import chat.homebuddy.core.network.toApiError
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class RetrofitProfileRemoteDataSourceTest {
    private val server = MockWebServer()
    private lateinit var remote: RetrofitProfileRemoteDataSource

    @Before
    fun setUp() {
        server.start()
        remote = RetrofitProfileRemoteDataSource(apiFor(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `the profile is the signed-in user and their homes`() = runTest {
        server.enqueue(json("""{"id": 1, "cognito_sub": "s", "email": "pat@example.com", "display_name": "Pat"}"""))
        server.enqueue(json("""[{"id": 7, "name": "Elm Street", "zip_code": "80202", "home_type": "condo", "role": "owner"}]"""))

        assertEquals("pat@example.com", remote.me().email)
        assertEquals(listOf(HouseholdDto(7, "Elm Street", "80202", "condo", "owner")), remote.households())
        assertEquals("/auth/me", server.takeRequest().path)
        assertEquals("/households", server.takeRequest().path)
    }

    @Test
    fun `a new home is sent with its details`() = runTest {
        server.enqueue(json("""{"id": 8, "name": "Cabin", "zip_code": "80424", "home_type": "other", "role": "owner"}""", code = 201))

        val created = remote.createHousehold("Cabin", "80424", "other")

        assertEquals(8, created.id)
        val request = server.takeRequest()
        assertEquals("POST /households", "${request.method} ${request.path}")
        assertEquals("""{"name":"Cabin","zip_code":"80424","home_type":"other"}""", request.body.readUtf8())
    }

    @Test
    fun `deleting the account succeeds when the server says it's gone`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        remote.deleteAccount()

        val request = server.takeRequest()
        assertEquals("DELETE /me", "${request.method} ${request.path}")
    }

    @Test
    fun `a deletion the server couldn't finish throws with its message`() = runTest {
        server.enqueue(json("""{"detail": "Couldn't finish deleting your sign-in. Please try again."}""", code = 502))

        try {
            remote.deleteAccount()
            fail("expected an HttpException")
        } catch (error: HttpException) {
            assertEquals(502, error.code())
            assertEquals("Couldn't finish deleting your sign-in. Please try again.", error.toApiError().message)
        }
    }
}
