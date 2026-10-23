package chat.homebuddy.app

import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import chat.homebuddy.designsystem.uiText
import chat.homebuddy.feature.auth.AppUiState
import chat.homebuddy.feature.auth.SignInDestination
import chat.homebuddy.feature.auth.SignInUiState
import chat.homebuddy.feature.auth.signInScreen
import chat.homebuddy.feature.home.HomeSetupDestination
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The root graph's rules, on the real graph (nothing is drawn). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppNavigationTest {
    private val navController = TestNavHostController(ApplicationProvider.getApplicationContext())

    @Before
    fun setUp() {
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        navController.graph = navController.createGraph(startDestination = SignInDestination) {
            appDestinations(signInState = { SignInUiState.Idle() }, onSignIn = {}, pendingShare = { null }, onShareOpened = {}, onSignOut = {})
        }
    }

    private fun stack(): List<KClass<*>> = navController.currentBackStack.value
        .mapNotNull { entry -> ROUTES.firstOrNull { entry.destination.hasRoute(it) } }

    @Test
    fun `the app opens on sign-in and stays put while the session loads`() {
        assertEquals(listOf(SignInDestination::class), stack())
        assertNull(AppUiState.Loading().destination())
    }

    @Test
    fun `signing in leaves nothing behind the app, so back leaves the app`() {
        navController.navigateForSession(AppUiState.Main.destination()!!)

        assertEquals(listOf(MainDestination::class), stack())
    }

    @Test
    fun `a new account sets up its home, then goes into the app with nothing behind it`() {
        navController.navigateForSession(AppUiState.HomeSetup.destination()!!)
        assertEquals(listOf(HomeSetupDestination::class), stack())

        navController.navigateForSession(MainDestination)

        assertEquals(listOf(MainDestination::class), stack())
    }

    @Test
    fun `signing out leaves only sign-in, so back can't go into the app`() {
        navController.navigateForSession(MainDestination)

        navController.navigateForSession(AppUiState.SignedOut(SignInUiState.Idle()).destination()!!)

        assertEquals(listOf(SignInDestination::class), stack())
    }

    @Test
    fun `the same destination again changes nothing`() {
        navController.navigateForSession(MainDestination)
        val entry = navController.currentBackStackEntry!!.id

        navController.navigateForSession(MainDestination)

        assertEquals(entry, navController.currentBackStackEntry!!.id)
    }

    @Test
    fun `the sign-in screen shows progress while loading, and the error when the profile couldn't load`() {
        assertEquals(SignInUiState.InProgress, AppUiState.Loading().signInScreen())
        val offline = uiText(R.string.error_offline)
        assertEquals(SignInUiState.Error(offline), AppUiState.Loading(offline).signInScreen())
    }

    private companion object {
        val ROUTES = listOf(SignInDestination::class, HomeSetupDestination::class, MainDestination::class)
    }
}
