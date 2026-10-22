package chat.homebuddy.feature.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import chat.homebuddy.designsystem.HomeBuddyTheme
import chat.homebuddy.designsystem.asUiText
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
class AccountScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val account = AccountUiState(name = "Pat Doe", email = "pat@example.com", householdName = "Elm Street")

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { HomeBuddyTheme(darkTheme = dark) { content() } }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun account() = capture("account") { AccountScreen(account, AccountActions()) }

    @Test
    fun accountDark() = capture("account_dark", dark = true) { AccountScreen(account, AccountActions()) }

    @Test
    fun deleting() = capture("account_deleting") { AccountScreen(account.copy(deleting = true), AccountActions()) }

    @Test
    fun deleteFailed() = capture("account_delete_failed") {
        AccountScreen(
            account.copy(error = "Your Home Buddy data was deleted, but your sign-in account couldn't be closed. Please try again.".asUiText()),
            AccountActions(),
        )
    }

}
