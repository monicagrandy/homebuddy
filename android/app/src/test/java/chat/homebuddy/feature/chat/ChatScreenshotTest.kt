package chat.homebuddy.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import chat.homebuddy.app.R
import chat.homebuddy.core.network.SourceDto
import chat.homebuddy.designsystem.HomeBuddyTheme
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ChatScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val answer = """
        Here's how to clean the filter:

        1. Turn the dishwasher **off** and remove the bottom rack.
        2. Twist the filter counterclockwise and lift it out.
        3. Rinse it under warm water; don't use a wire brush.
    """.trimIndent()

    private val sources = listOf(SourceDto("bosch", "Bosch Dishwasher Manual", 12, "manual", null))

    private fun capture(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HomeBuddyTheme(darkTheme = dark) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val conversations = ConversationListUiState(
        loaded = true,
        items = listOf(
            ConversationItem("1", "How do I clean the dishwasher filter?", uiText(R.string.chat_updated_today, "9:05 AM")),
            ConversationItem("2", "Is my furnace still under warranty?", "Oct 19".asUiText()),
            ConversationItem("3", "What does error E15 mean on the Bosch dishwasher? It flashes after every cycle", "Sep 30".asUiText()),
        ),
    )

    @Test
    fun conversationList() = capture("chat_list") { ConversationListScreen(conversations, ConversationListActions()) }

    @Test
    fun emptyList() = capture("chat_list_empty") { ConversationListScreen(ConversationListUiState(loaded = true), ConversationListActions()) }

    @Test
    fun conversationListDark() = capture("chat_list_dark", dark = true) { ConversationListScreen(conversations, ConversationListActions()) }

    @Test
    fun conversationListLargeFont() = capture("chat_list_font_150", fontScale = 1.5f) { ConversationListScreen(conversations, ConversationListActions()) }

    @Test
    @Config(qualifiers = "w400dp-h400dp-mdpi")
    fun conversationList400x400() = capture("chat_list_400x400") { ConversationListScreen(conversations, ConversationListActions()) }

    @Test
    @Config(qualifiers = "w400dp-h500dp-mdpi")
    fun conversationList400x500() = capture("chat_list_400x500") { ConversationListScreen(conversations, ConversationListActions()) }

    @Test
    @Config(qualifiers = "w400dp-h1000dp-mdpi")
    fun conversationList400x1000() = capture("chat_list_400x1000") { ConversationListScreen(conversations, ConversationListActions()) }

    @Test
    @Config(qualifiers = "w610dp-h400dp-mdpi")
    fun conversationList610x400() = capture("chat_list_610x400") { ConversationListScreen(conversations, ConversationListActions()) }

    @Test
    @Config(qualifiers = "w610dp-h500dp-mdpi")
    fun conversationList610x500() = capture("chat_list_610x500") { ConversationListScreen(conversations, ConversationListActions()) }

    @Test
    @Config(qualifiers = "w610dp-h1000dp-mdpi")
    fun conversationList610x1000() = capture("chat_list_610x1000") { ConversationListScreen(conversations, ConversationListActions()) }

    @Test
    @Config(qualifiers = "w900dp-h400dp-mdpi")
    fun conversationList900x400() = capture("chat_list_900x400") { ConversationListScreen(conversations, ConversationListActions()) }

    @Test
    @Config(qualifiers = "w900dp-h500dp-mdpi")
    fun conversationList900x500() = capture("chat_list_900x500") { ConversationListScreen(conversations, ConversationListActions()) }

    @Test
    @Config(qualifiers = "w900dp-h1000dp-mdpi")
    fun conversationList900x1000() = capture("chat_list_900x1000") { ConversationListScreen(conversations, ConversationListActions()) }

    @Test
    fun streaming() = capture("chat_streaming") {
        ConversationScreen(
            ConversationUiState(
                title = "How do I clean the dishwasher filter?",
                turns = listOf(ChatTurn("How do I clean the dishwasher filter?", status = "Document agent checked the saved docs and finished its pass.".asUiText())),
            ),
            ConversationActions(),
        )
    }

    @Test
    fun answered() = capture("chat_answer_with_sources") {
        ConversationScreen(
            ConversationUiState(
                title = "How do I clean the dishwasher filter?",
                history = listOf(HistoryMessage(true, "My dishwasher smells bad."), HistoryMessage(false, "A dirty filter is the usual cause.")),
                turns = listOf(ChatTurn("How do I clean the dishwasher filter?", answer = answer, status = null, streaming = false, sources = sources)),
            ),
            ConversationActions(),
        )
    }

    private val gas = ConversationUiState(
        title = "I smell gas in the kitchen",
        turns = listOf(
            ChatTurn(
                "I smell gas in the kitchen",
                answer = "Leave your home now and call 911 or your gas company from outside. Don't switch lights on or off.",
                status = null,
                streaming = false,
                safety = SafetyAlert(critical = true),
            ),
        ),
    )

    @Test
    fun safety() = capture("chat_safety") { ConversationScreen(gas, ConversationActions()) }

    @Test
    fun safetyDark() = capture("chat_safety_dark", dark = true) { ConversationScreen(gas, ConversationActions()) }

    /** Reopened later or on another device, the same answers look as they did live. */
    @Test
    fun reopened() = capture("chat_reopened_with_safety_and_sources") {
        ConversationScreen(
            ConversationUiState(
                title = "I smell gas in the kitchen",
                history = listOf(
                    HistoryMessage(true, "I smell gas in the kitchen", id = 5),
                    HistoryMessage(false, gas.turns.single().answer, id = 6, safety = SafetyAlert(critical = true)),
                    HistoryMessage(true, "How do I clean the dishwasher filter?", id = 7),
                    HistoryMessage(false, answer, id = 8, sources = sources),
                ),
            ),
            ConversationActions(),
        )
    }

    @Test
    fun busyElsewhere() = capture("chat_busy_409") {
        ConversationScreen(
            ConversationUiState(
                title = "Is my furnace still under warranty?",
                draft = "Are you there?",
                message = "Still answering your last question in this conversation. Try again in a moment.".asUiText(),
            ),
            ConversationActions(),
        )
    }

    @Test
    fun dictating() = capture("chat_dictating") {
        ConversationScreen(ConversationUiState(draft = "why is my dishwasher leaking", listening = true), ConversationActions())
    }

    @Test
    fun largestFont() = capture("chat_answer_font_200", fontScale = 2f) {
        ConversationScreen(
            ConversationUiState(turns = listOf(ChatTurn("How do I clean the filter?", answer = answer, status = null, streaming = false, sources = sources))),
            ConversationActions(),
        )
    }
}
