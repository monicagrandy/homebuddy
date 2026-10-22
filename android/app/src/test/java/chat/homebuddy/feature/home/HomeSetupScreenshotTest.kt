package chat.homebuddy.feature.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import chat.homebuddy.designsystem.HomeBuddyTheme
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
class HomeSetupScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, state: HomeSetupUiState, dark: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent { HomeBuddyTheme(darkTheme = dark) { HomeSetupScreen(state, onSubmit = {}) } }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun form() = capture("home_setup", HomeSetupUiState())

    @Test
    fun formDark() = capture("home_setup_dark", HomeSetupUiState(), dark = true)

    @Test
    fun saving() = capture("home_setup_saving", HomeSetupUiState(saving = true))

    @Test
    fun serverError() = capture("home_setup_error", HomeSetupUiState(error = "Couldn't reach Home Buddy. Check your connection and try again."))
}
