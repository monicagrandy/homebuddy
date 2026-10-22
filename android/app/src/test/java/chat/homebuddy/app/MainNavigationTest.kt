package chat.homebuddy.app

import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import chat.homebuddy.feature.account.AccountDestination
import chat.homebuddy.feature.chat.ConversationDestination
import chat.homebuddy.feature.chat.ConversationListDestination
import chat.homebuddy.feature.documents.DocumentsDestination
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The signed-in app's navigation rules, on the real graph (nothing is drawn). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainNavigationTest {
    private val navController = TestNavHostController(ApplicationProvider.getApplicationContext())

    @Before
    fun setUp() {
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        navController.graph = navController.createGraph(startDestination = ConversationListDestination) {
            mainDestinations(navController, onSignOut = {})
        }
    }

    private fun stack(): List<KClass<*>> = navController.currentBackStack.value
        .mapNotNull { entry -> ROUTES.firstOrNull { entry.destination.hasRoute(it) } }

    @Test
    fun `chat is where the app starts`() {
        assertEquals(listOf(ConversationListDestination::class), stack())
    }

    @Test
    fun `each tab has one entry, and back from any tab goes to chat`() {
        navController.navigateToTab(DocumentsDestination(), restoreState = true)
        navController.navigateToTab(AccountDestination, restoreState = true)
        navController.navigateToTab(DocumentsDestination(), restoreState = true)

        assertEquals(listOf(ConversationListDestination::class, DocumentsDestination::class), stack())
        navController.popBackStack()
        assertEquals(listOf(ConversationListDestination::class), stack())
    }

    @Test
    fun `coming back to a tab restores it`() {
        navController.navigateToTab(DocumentsDestination(), restoreState = true)
        val documents = navController.currentBackStackEntry!!.id

        navController.navigateToTab(AccountDestination, restoreState = true)
        navController.navigateToTab(DocumentsDestination(), restoreState = true)

        assertEquals(documents, navController.currentBackStackEntry!!.id)
    }

    @Test
    fun `a conversation opens over the list and back returns to it`() {
        navController.navigate(ConversationDestination("thread-1"))

        assertEquals("thread-1", navController.currentBackStackEntry!!.toRoute<ConversationDestination>().id)
        navController.popBackStack()
        assertEquals(listOf(ConversationListDestination::class), stack())
    }

    @Test
    fun `a share opens the documents tab with what was shared, even when it was open already`() {
        navController.navigateToTab(DocumentsDestination(), restoreState = true)

        navController.navigateToTab(DocumentsDestination(sharedLink = "https://example.com/manual.pdf"), restoreState = false)

        assertEquals(listOf(ConversationListDestination::class, DocumentsDestination::class), stack())
        val shared = navController.currentBackStackEntry!!.toRoute<DocumentsDestination>()
        assertEquals("https://example.com/manual.pdf", shared.sharedLink)
        assertTrue(shared.sharedFile == null)
    }

    private companion object {
        val ROUTES = listOf(ConversationListDestination::class, ConversationDestination::class, DocumentsDestination::class, AccountDestination::class)
    }
}
