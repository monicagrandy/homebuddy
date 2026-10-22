package chat.homebuddy.core.auth

import android.content.Intent
import app.cash.turbine.test
import chat.homebuddy.core.network.BootstrapRequest
import chat.homebuddy.core.network.ClientConfigDto
import chat.homebuddy.core.network.HomeBuddyApi
import chat.homebuddy.core.network.HouseholdCreateRequest
import chat.homebuddy.core.network.HouseholdDto
import chat.homebuddy.core.network.UserDto
import java.io.IOException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private val PAT = UserDto(id = 1, cognitoSub = "sub-1", email = "pat@example.com", displayName = "Pat")
private val HOME = HouseholdDto(id = 7, name = "Elm Street", zipCode = "80202", homeType = "condo", role = "owner")

private fun httpError(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

class FakeAuthClient : AuthClient {
    var hasSavedSession = false
    var signIn: () -> AuthTokens = { AuthTokens(accessToken = "access-1", idToken = "id-1") }
    var tokens: AuthTokens? = AuthTokens("access-1", "id-1")
    var clears = 0

    override fun signInIntent(): Intent = error("not used in unit tests")

    override suspend fun completeSignIn(result: Intent?): AuthTokens = signIn().also { hasSavedSession = true }

    override suspend fun tokens(forceRefresh: Boolean): AuthTokens? = if (hasSavedSession) tokens else null

    override suspend fun hasSession(): Boolean = hasSavedSession

    override suspend fun clear() {
        hasSavedSession = false
        clears++
    }

    override fun signOutUrl(): String = "https://hosted.example/logout?client_id=c"
}

class FakeApi : HomeBuddyApi {
    var user: suspend () -> UserDto = { PAT }
    var households: suspend () -> List<HouseholdDto> = { listOf(HOME) }
    val bootstraps = mutableListOf<BootstrapRequest>()

    override suspend fun bootstrap(body: BootstrapRequest): UserDto {
        bootstraps += body
        return user()
    }

    override suspend fun me(): UserDto = user()

    override suspend fun households(): List<HouseholdDto> = households.invoke()

    override suspend fun createHousehold(body: HouseholdCreateRequest): HouseholdDto = error("not used")

    override suspend fun clientConfig(): ClientConfigDto = error("not used")
}

class SessionRepositoryTest {
    private val auth = FakeAuthClient()
    private val api = FakeApi()
    private var wipes = 0

    private fun TestScope.repository() = SessionRepository(auth, api, setOf(LocalDataWiper { wipes++ }), backgroundScope)

    @Test
    fun `starts out restoring, then signed out when nothing is saved`() = runTest {
        val session = repository()
        assertEquals(SessionState.Restoring, session.state.value)

        session.restore()

        assertEquals(SessionState.SignedOut(), session.state.value)
    }

    @Test
    fun `a saved session restores the profile and households`() = runTest {
        auth.hasSavedSession = true
        val session = repository()

        session.restore()

        val signedIn = session.state.value as SessionState.SignedIn
        assertEquals(User(1, "pat@example.com", "Pat"), signedIn.user)
        assertEquals(listOf(Household(7, "Elm Street", "80202", "condo", "owner")), signedIn.households)
        assertTrue(!signedIn.needsHome)
    }

    @Test
    fun `an account without a household needs home setup`() = runTest {
        auth.hasSavedSession = true
        api.households = { emptyList() }
        val session = repository()

        session.restore()

        assertTrue((session.state.value as SessionState.SignedIn).needsHome)
    }

    @Test
    fun `an account deleted on another device signs this one out and wipes it`() = runTest {
        auth.hasSavedSession = true
        api.user = { throw httpError(401) }
        val session = repository()

        session.restore()

        assertEquals(SessionState.SignedOut(SessionRepository.SESSION_ENDED), session.state.value)
        assertEquals(1, auth.clears)
        assertEquals(1, wipes)
    }

    @Test
    fun `being offline at launch keeps the session for later`() = runTest {
        auth.hasSavedSession = true
        api.user = { throw IOException("offline") }
        val session = repository()

        session.restore()

        assertEquals(SessionState.Failed(SessionRepository.OFFLINE), session.state.value)
        assertEquals(0, auth.clears)
    }

    @Test
    fun `signing in bootstraps the profile with both tokens`() = runTest {
        val session = repository()

        session.state.test {
            assertEquals(SessionState.Restoring, awaitItem())
            session.completeSignIn(null)
            assertEquals(SessionState.SigningIn, awaitItem())
            assertTrue(awaitItem() is SessionState.SignedIn)
        }
        assertEquals(listOf(BootstrapRequest(idToken = "id-1", accessToken = "access-1")), api.bootstraps)
    }

    @Test
    fun `cancelling sign-in goes back to signed out`() = runTest {
        auth.signIn = { throw SignInCancelled() }
        val session = repository()

        session.completeSignIn(null)

        assertEquals(SessionState.SignedOut(), session.state.value)
    }

    @Test
    fun `a failed sign-in shows an error`() = runTest {
        auth.signIn = { throw SignInFailed("invalid_grant") }
        val session = repository()

        session.completeSignIn(null)

        assertEquals(SessionState.Failed(SessionRepository.SIGN_IN_FAILED), session.state.value)
    }

    @Test
    fun `sign-in without an ID token is refused and forgotten`() = runTest {
        auth.signIn = { AuthTokens(accessToken = "access-1", idToken = null) }
        val session = repository()

        session.completeSignIn(null)

        assertEquals(SessionState.Failed(SessionRepository.SIGN_IN_FAILED), session.state.value)
        assertEquals(1, auth.clears)
    }

    @Test
    fun `an account outside the beta gets a clear message`() = runTest {
        api.user = { throw httpError(403) }
        val session = repository()

        session.completeSignIn(null)

        assertEquals(SessionState.Failed(SessionRepository.NOT_IN_BETA), session.state.value)
        assertEquals(1, auth.clears)
    }

    @Test
    fun `signing out wipes this device and returns the logout page`() = runTest {
        auth.hasSavedSession = true
        val session = repository()
        session.restore()

        val logout = session.signOut()

        assertEquals("https://hosted.example/logout?client_id=c", logout)
        assertEquals(SessionState.SignedOut(), session.state.value)
        assertEquals(1, wipes)
        assertNull(session.accessToken())
    }

    @Test
    fun `when the API rejects the session it ends with a message`() = runTest {
        auth.hasSavedSession = true
        val session = repository()
        session.restore()

        session.onSessionRejected()
        runCurrent()

        assertEquals(SessionState.SignedOut(SessionRepository.SESSION_ENDED), session.state.value)
        assertEquals(1, wipes)
    }

    @Test
    fun `access tokens come from the auth client`() = runTest {
        auth.hasSavedSession = true

        assertEquals("access-1", repository().accessToken())
    }

    @Test
    fun `a newly created household joins the signed-in state`() = runTest {
        auth.hasSavedSession = true
        api.households = { emptyList() }
        val session = repository()
        session.restore()

        session.onHouseholdCreated(Household(8, "Cabin", "80424", "cabin", "owner"))

        assertEquals(listOf("Cabin"), (session.state.value as SessionState.SignedIn).households.map { it.name })
    }
}
