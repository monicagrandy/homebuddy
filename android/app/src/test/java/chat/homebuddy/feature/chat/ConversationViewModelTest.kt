package chat.homebuddy.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import chat.homebuddy.app.R
import chat.homebuddy.core.data.CachedConversationsRepository
import chat.homebuddy.core.data.ConversationsRepository
import chat.homebuddy.core.data.HomeBuddyDatabase
import chat.homebuddy.core.data.profile.CachedProfileRepository
import chat.homebuddy.core.data.profile.ProfileRepository
import chat.homebuddy.core.data.profile.RetrofitProfileRemoteDataSource
import chat.homebuddy.core.data.profile.RoomProfileLocalDataSource
import chat.homebuddy.core.domain.ObserveCurrentHouseholdUseCase
import chat.homebuddy.core.network.AccessTokenProvider
import chat.homebuddy.core.network.ApiConfig
import chat.homebuddy.core.network.QueryStreamClient
import chat.homebuddy.core.network.buildApi
import chat.homebuddy.core.network.buildHttpClient
import chat.homebuddy.core.testing.ContractFixtures
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private object SignedIn : AccessTokenProvider {
    override suspend fun accessToken(forceRefresh: Boolean) = "token"
    override suspend fun onSessionRejected() = Unit
}

/** The real ViewModel, repositories, Room and HTTP client, against a fake backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationViewModelTest {
    private val server = MockWebServer()
    private val streamRequests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var streamResponse: MockResponse = MockResponse()
    private lateinit var database: HomeBuddyDatabase
    private lateinit var conversations: ConversationsRepository
    private lateinit var profile: ProfileRepository
    private lateinit var viewModel: ConversationViewModel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        streamResponse = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(ContractFixtures.read("query_stream_answer.sse"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/auth/me") -> json("""{"id": 1, "cognito_sub": "s", "email": "pat@example.com", "display_name": "Pat"}""")
                    path.startsWith("/households") -> json("""[{"id": 7, "name": "Elm Street", "zip_code": "80202", "home_type": "condo", "role": "owner"}]""")
                    path.startsWith("/query/stream") -> streamResponse.also { streamRequests += request }
                    path.startsWith("/conversations/gone/messages") -> MockResponse().setResponseCode(404).setBody("""{"detail": "Conversation not found."}""")
                    path.startsWith("/conversations") && path.contains("/messages") -> json("[]")
                    path.startsWith("/conversations") -> json("""[{"id": "thread-1", "household_id": 7, "title": "Dishwasher filter", "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-01T00:00:00Z"}]""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val config = ApiConfig(server.url("/").toString(), "android/test")
        val client = buildHttpClient(config, Provider<AccessTokenProvider> { SignedIn })
        val api = buildApi(config, client)
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HomeBuddyDatabase::class.java).build()
        profile = CachedProfileRepository(RetrofitProfileRemoteDataSource(api), RoomProfileLocalDataSource(database.profile()))
        profile.refresh()
        conversations = CachedConversationsRepository(api, QueryStreamClient(client, config), database.conversations())
        viewModel = conversation()
        Unit
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    /** A thread's screen; with no [id], a new thread. */
    private fun conversation(id: String? = null) =
        ConversationViewModel(SavedStateHandle(mapOf("id" to id)), ObserveCurrentHouseholdUseCase(profile), conversations)
            .also { viewModel -> scope.launch { viewModel.state.collect {} } }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun awaitConversation(condition: (ConversationUiState) -> Boolean): ConversationUiState = runBlocking {
        withTimeout(5_000) {
            while (true) {
                viewModel.state.value.takeIf(condition)?.let { return@withTimeout it }
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    @Test
    fun `dictation fills the draft and never sends on its own`() {
        viewModel.onDraftChange("Kitchen:")
        viewModel.onDictationStarted()
        viewModel.onDictationResult("why is my dishwasher", final = false)
        viewModel.onDictationResult("why is my dishwasher leaking", final = true)

        val state = awaitConversation { it.draft == "Kitchen: why is my dishwasher leaking" }

        assertFalse(state.listening)
        assertTrue(state.turns.isEmpty())
        runBlocking { delay(200) }
        assertTrue("nothing was sent", streamRequests.isEmpty())
    }

    @Test
    fun `sending streams the answer with its sources`() {
        viewModel.onDraftChange("How do I clean the dishwasher filter?")
        viewModel.send()

        val state = awaitConversation { it.turns.singleOrNull()?.streaming == false }

        val turn = state.turns.single()
        assertTrue(turn.answer.startsWith("Twist the filter counterclockwise"))
        assertEquals("Bosch Dishwasher Manual", turn.sources.single().displayName)
        assertEquals("", state.draft)
        assertEquals(1, streamRequests.size)
        assertTrue(streamRequests.single().body.readUtf8().contains("\"household_zip_code\":\"80202\""))
    }

    @Test
    fun `a gas question shows the safety card`() {
        streamResponse = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(ContractFixtures.read("query_stream_safety.sse"))
        viewModel.onDraftChange("I smell gas in the kitchen")
        viewModel.send()

        val turn = awaitConversation { it.turns.singleOrNull()?.streaming == false }.turns.single()

        assertEquals(SafetyAlert(critical = true), turn.safety)
    }

    @Test
    fun `a thread deleted on another device closes and says why`() {
        val list = ConversationListViewModel(ObserveCurrentHouseholdUseCase(profile), conversations)
        scope.launch { list.state.collect {} }

        val gone = conversation(id = "gone")
        runBlocking { withTimeout(5_000) { gone.gone.first { it } } }
        list.onConversationGone()

        assertEquals(uiText(R.string.chat_conversation_gone), list.state.value.message)
    }

    @Test
    fun `the list refreshes even before the cached profile has been read`() {
        val list = ConversationListViewModel(ObserveCurrentHouseholdUseCase(profile), conversations)
        scope.launch { list.state.collect {} }

        list.refresh()

        val items = runBlocking { withTimeout(5_000) { list.state.first { it.items.isNotEmpty() } }.items }
        assertEquals("Dishwasher filter", items.single().title)
    }

    @Test
    fun `when another device is still being answered the question is kept to resend`() {
        streamResponse = MockResponse().setResponseCode(409)
            .setBody("""{"detail": "Still answering your last question in this conversation. Try again in a moment."}""")
        viewModel.onDraftChange("Are you there?")
        viewModel.send()

        val state = awaitConversation { it.message != null }

        assertTrue(state.turns.isEmpty())
        assertEquals("Are you there?", state.draft)
        assertEquals("Still answering your last question in this conversation. Try again in a moment.".asUiText(), state.message)
    }
}
