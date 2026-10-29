package chat.homebuddy.feature.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import chat.homebuddy.designsystem.HomeBuddyTheme
import chat.homebuddy.designsystem.asUiText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ConversationListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val notice = "History sync is limited to your 100 most recent conversations."

    @Test
    fun `history limit stays visible after scrolling and screen restoration`() {
        val restoration = StateRestorationTester(compose)
        val state = ConversationListUiState(
            loaded = true,
            items = (1..100).map { ConversationItem("$it", "Conversation $it", "Today".asUiText()) },
        )
        restoration.setContent {
            HomeBuddyTheme { ConversationListScreen(state, ConversationListActions()) }
        }
        compose.onNodeWithText(notice).assertIsDisplayed()

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(99)

        compose.onNodeWithText("Conversation 100").assertIsDisplayed()
        compose.onNodeWithText(notice).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(notice).assertIsDisplayed()
    }

    @Test
    fun `history limit remains visible during loading refresh empty history and error dismissal`() {
        val state = mutableStateOf(ConversationListUiState())
        compose.setContent {
            HomeBuddyTheme {
                ConversationListScreen(
                    state.value,
                    ConversationListActions(onMessageShown = { state.value = state.value.copy(message = null) }),
                )
            }
        }
        compose.onNodeWithText(notice).assertIsDisplayed()
        compose.runOnIdle { state.value = ConversationListUiState(loaded = true, refreshing = true) }
        compose.onNodeWithText(notice).assertIsDisplayed()
        compose.runOnIdle {
            state.value = ConversationListUiState(loaded = true, message = "Showing saved history.".asUiText())
        }
        compose.onNodeWithText(notice).assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Showing saved history.").assertDoesNotExist()
        compose.onNodeWithText(notice).assertIsDisplayed()
    }
}
