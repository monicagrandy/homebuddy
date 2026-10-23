package chat.homebuddy.app

import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import chat.homebuddy.feature.auth.SignInDestination
import chat.homebuddy.feature.chat.ConversationListDestination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class TabViewModel : ViewModel() {
    var cleared = false

    override fun onCleared() {
        cleared = true
    }
}

/**
 * How long ViewModels live, with the app's two NavHosts arranged as they are in HomeBuddyRoot and
 * MainScaffold. Nothing is tapped; it only composes and navigates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SignedInScopeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var root: NavHostController
    private var tab: TabViewModel? = null

    private fun setUpApp() {
        compose.setContent {
            root = rememberNavController()
            NavHost(root, startDestination = SignInDestination, enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None }) {
                composable<SignInDestination> {}
                composable<MainDestination> {
                    val tabs = rememberNavController()
                    NavHost(tabs, startDestination = ConversationListDestination) {
                        composable<ConversationListDestination> { tab = viewModel() }
                    }
                }
            }
        }
        compose.runOnIdle { root.navigateForSession(MainDestination) }
        compose.waitForIdle()
    }

    @Test
    fun `back in the signed-in app is left to the system, which closes the app`() {
        setUpApp()

        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `signing out clears the ViewModels created in the signed-in app`() {
        setUpApp()
        val signedIn = checkNotNull(tab)

        compose.runOnIdle { root.navigateForSession(SignInDestination) }
        compose.waitForIdle()

        assertTrue(signedIn.cleared)
    }
}
