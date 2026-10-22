package chat.homebuddy.core.domain

import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import chat.homebuddy.core.auth.AccessTokenCache
import chat.homebuddy.core.auth.AuthClient
import chat.homebuddy.core.auth.AuthRepository
import chat.homebuddy.core.auth.AuthStore
import chat.homebuddy.core.auth.SignInCancelled
import chat.homebuddy.core.auth.SignOutReason
import chat.homebuddy.core.auth.TokenSet
import chat.homebuddy.core.auth.XorCipher
import chat.homebuddy.core.data.ConversationsRepository
import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.DocumentsRepository
import chat.homebuddy.core.data.FakeScheduler
import chat.homebuddy.core.data.HomeBuddyDatabase
import chat.homebuddy.core.data.ProfileRepository
import chat.homebuddy.core.data.UploadFiles
import chat.homebuddy.core.data.inMemoryDatabase
import chat.homebuddy.core.network.ApiConfig
import chat.homebuddy.core.network.QueryStreamClient
import chat.homebuddy.core.network.buildApi
import chat.homebuddy.core.network.buildHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeAuthClient : AuthClient {
    var exchange: () -> TokenSet = { TokenSet("access-1", "id-1", "refresh-1", expiresAtMillis = null) }
    var refresh: (String) -> TokenSet? = { TokenSet("access-2", "id-2", refreshToken = null, expiresAtMillis = null) }

    override fun signInIntent() = Intent("sign-in")
    override suspend fun exchange(result: Intent?) = exchange()
    override suspend fun refresh(refreshToken: String) = refresh.invoke(refreshToken)
    override fun signOutUrl() = "https://auth.example.com/logout"
}

/** The real use cases, repositories, DataStore, Room and HTTP client, against a fake Cognito and backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionUseCasesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val routes = ConcurrentHashMap<String, () -> MockResponse>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authClient = FakeAuthClient()
    private val cache = AccessTokenCache()
    private lateinit var database: HomeBuddyDatabase
    private lateinit var store: AuthStore
    private lateinit var auth: AuthRepository
    private lateinit var profile: ProfileRepository
    private lateinit var observeSession: ObserveSessionUseCase
    private lateinit var signIn: SignInUseCase
    private lateinit var signOut: SignOutUseCase
    private lateinit var refreshProfile: RefreshProfileUseCase
    private lateinit var deleteAccount: DeleteAccountUseCase

    private val user = """{"id": 1, "cognito_sub": "s", "email": "pat@example.com", "display_name": "Pat"}"""
    private val home = """{"id": 7, "name": "Elm Street", "zip_code": "80202", "home_type": "condo", "role": "owner"}"""

    @Before
    fun setUp() {
        routes["POST /auth/bootstrap"] = { json(user) }
        routes["GET /auth/me"] = { json(user) }
        routes["GET /households"] = { json("[$home]") }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val route = "${request.method} ${request.path.orEmpty().substringBefore('?')}"
                // Like the backend: no token, no access (bootstrap checks the tokens in its body instead).
                if (request.getHeader("Authorization") == null && route != "POST /auth/bootstrap") return status(401)
                return routes[route]?.invoke() ?: status(404)
            }
        }
        server.start()

        database = inMemoryDatabase()
        store = AuthStore(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "auth.preferences_pb") }, XorCipher())
        auth = AuthRepository(authClient, store, cache)
        lateinit var session: NetworkSession
        val config = ApiConfig(baseUrl = server.url("/").toString(), clientHeader = "android/test")
        val http = buildHttpClient(config, Provider { session })
        val api = buildApi(config, http)
        profile = ProfileRepository(api, database.profile())
        val documents = DocumentsRepository(api, database.documents(), database.uploads(), UploadFiles(folder.newFolder("uploads")), FakeScheduler())
        val conversations = ConversationsRepository(api, QueryStreamClient(http, config), database.conversations())
        signOut = SignOutUseCase(auth, profile, documents, conversations)
        session = NetworkSession(auth, signOut)
        observeSession = ObserveSessionUseCase(auth, profile)
        signIn = SignInUseCase(auth, profile)
        refreshProfile = RefreshProfileUseCase(auth, profile, signOut)
        deleteAccount = DeleteAccountUseCase(profile, signOut)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun status(code: Int, body: String = """{"detail": "nope"}""") = MockResponse().setResponseCode(code).setBody(body)

    private fun session() = runBlocking { observeSession().first() }

    private fun signedIn() = runBlocking {
        assertEquals(SignInResult.SignedIn, signIn(Intent()))
    }

    @Test
    fun `a new install is signed out`() {
        assertEquals(SessionState.SignedOut(reason = null), session())
    }

    @Test
    fun `signing in sets up the profile and stores only the refresh token`() = runBlocking {
        routes["GET /households"] = { json("[]") }

        assertEquals(SignInResult.SignedIn, signIn(Intent()))

        val state = session() as SessionState.SignedIn
        assertEquals("pat@example.com", state.user.email)
        assertTrue(state.needsHome)
        assertEquals("refresh-1", store.stored.first().refreshToken)
        assertEquals("""{"id_token":"id-1","access_token":"access-1"}""", server.takeRequest().body.readUtf8())
        val onDisk = folder.root.resolve("auth.preferences_pb").readText(Charsets.ISO_8859_1)
        assertFalse(onDisk.contains("refresh-1"))
        assertFalse(onDisk.contains("access-1"))
    }

    @Test
    fun `a sign-in whose access token expires during setup keeps its refresh token`() = runBlocking {
        authClient.exchange = { TokenSet("access-1", "id-1", "refresh-1", expiresAtMillis = 0) }

        assertEquals(SignInResult.SignedIn, signIn(Intent()))

        assertEquals("refresh-1", store.stored.first().refreshToken)
    }

    @Test
    fun `an account outside the beta is not kept signed in`() = runBlocking {
        routes["POST /auth/bootstrap"] = { status(403) }

        assertEquals(SignInResult.NotInBeta, signIn(Intent()))

        assertEquals(SessionState.SignedOut(reason = null), session())
        assertNull(auth.accessToken())
    }

    @Test
    fun `a sign-in that loses the connection half-way keeps nothing`() = runBlocking {
        routes["GET /households"] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }

        assertEquals(SignInResult.Offline, signIn(Intent()))

        assertEquals(SessionState.SignedOut(reason = null), session())
        assertNull(profile.profile.first())
    }

    @Test
    fun `closing the sign-in page changes nothing`() = runBlocking {
        authClient.exchange = { throw SignInCancelled() }

        assertEquals(SignInResult.Cancelled, signIn(Intent()))
        assertEquals(SessionState.SignedOut(reason = null), session())
    }

    @Test
    fun `trying to sign in again clears the last sign-out reason`() = runBlocking {
        store.clear(SignOutReason.ACCOUNT_DELETED)
        authClient.exchange = { throw SignInCancelled() }

        signIn(Intent())

        assertEquals(SessionState.SignedOut(reason = null), session())
    }

    @Test
    fun `signing out wipes this phone and returns the logout page`() = runBlocking {
        signedIn()
        database.documents().upsertAll(listOf(DocumentEntity(7, "bosch", "Bosch", "manual", "indexed", null, null)))

        assertEquals("https://auth.example.com/logout", signOut())

        assertEquals(SessionState.SignedOut(reason = null), session())
        assertEquals(emptyList<DocumentEntity>(), database.documents().observe(7).first())
        assertNull(profile.profile.first())
        assertNull(store.stored.first().refreshToken)
    }

    @Test
    fun `a session the server rejects ends with a reason`() = runBlocking {
        signedIn()
        routes["GET /auth/me"] = { status(401) }

        assertTrue(refreshProfile())

        assertEquals(SessionState.SignedOut(SignOutReason.SESSION_ENDED), session())
        assertNull(profile.profile.first())
    }

    @Test
    fun `an expired refresh token ends the session on the next request`() = runBlocking {
        signedIn()
        cache.put(TokenSet("access-1", "id-1", "refresh-1", expiresAtMillis = 0))
        authClient.refresh = { null }

        refreshProfile()

        assertEquals(SessionState.SignedOut(SignOutReason.SESSION_ENDED), session())
    }

    @Test
    fun `being offline at launch keeps the session and the cached profile`() = runBlocking {
        signedIn()
        routes["GET /auth/me"] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }

        assertFalse(refreshProfile())

        assertEquals("Elm Street", (session() as SessionState.SignedIn).households.single().name)
    }

    @Test
    fun `an account removed from the beta is signed out at launch`() = runBlocking {
        signedIn()
        routes["GET /auth/me"] = { status(403) }

        refreshProfile()

        assertEquals(SessionState.SignedOut(SignOutReason.NOT_IN_BETA), session())
    }

    @Test
    fun `a profile changed on another device shows up after a refresh`() = runBlocking {
        signedIn()
        routes["GET /auth/me"] = { json("""{"id": 1, "cognito_sub": "s", "email": "pat@example.com", "display_name": "Patricia"}""") }

        assertTrue(refreshProfile())

        assertEquals("Patricia", (session() as SessionState.SignedIn).user.displayName)
    }

    @Test
    fun `creating a home moves the session on`() = runBlocking {
        routes["GET /households"] = { json("[]") }
        signedIn()
        routes["POST /households"] = { json(home) }

        profile.createHousehold("Elm Street", "80202", "condo")

        assertFalse((session() as SessionState.SignedIn).needsHome)
    }

    @Test
    fun `deleting the account signs out with a reason`() = runBlocking {
        signedIn()
        routes["DELETE /me"] = { status(204, body = "") }

        assertEquals(DeleteAccountResult.Deleted, deleteAccount())

        assertEquals(SessionState.SignedOut(SignOutReason.ACCOUNT_DELETED), session())
    }

    @Test
    fun `a deletion that fails keeps the session and says why`() = runBlocking {
        signedIn()
        routes["DELETE /me"] = { status(502, """{"detail": "Couldn't finish deleting your sign-in. Please try again."}""") }

        assertEquals(DeleteAccountResult.Failed("Couldn't finish deleting your sign-in. Please try again."), deleteAccount())

        assertTrue(session() is SessionState.SignedIn)
    }
}
