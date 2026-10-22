package chat.homebuddy.feature.auth

import chat.homebuddy.core.designsystem.HomeBuddyTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
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
class SignInScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HomeBuddyTheme(darkTheme = dark) { content() }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun idle() = capture("sign_in_idle") { SignInScreen(SignInUiState.Idle(), onSignIn = {}) }

    @Test
    fun idleDark() = capture("sign_in_idle_dark", dark = true) { SignInScreen(SignInUiState.Idle(), onSignIn = {}) }

    @Test
    fun signedOutElsewhere() = capture("sign_in_signed_out_message") {
        SignInScreen(SignInUiState.Idle("You've been signed out. Please sign in again."), onSignIn = {})
    }

    @Test
    fun inProgress() {
        // The spinner never settles, so freeze the clock rather than waiting for idle.
        compose.mainClock.autoAdvance = false
        capture("sign_in_in_progress") { SignInScreen(SignInUiState.InProgress, onSignIn = {}) }
    }

    @Test
    fun error() = capture("sign_in_error") {
        SignInScreen(SignInUiState.Error("Couldn't reach Home Buddy. Check your connection and try again."), onSignIn = {})
    }

    @Test
    fun errorDark() = capture("sign_in_error_dark", dark = true) {
        SignInScreen(SignInUiState.Error("This account isn't part of the Home Buddy beta yet."), onSignIn = {})
    }

    @Test
    fun largestFont() = capture("sign_in_font_200", fontScale = 2f) {
        SignInScreen(SignInUiState.Error("Couldn't reach Home Buddy. Check your connection and try again."), onSignIn = {})
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun smallPhone() = capture("sign_in_small_phone") { SignInScreen(SignInUiState.Idle(), onSignIn = {}) }
}
