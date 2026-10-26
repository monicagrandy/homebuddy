package chat.homebuddy.feature.home

import chat.homebuddy.app.MainDispatcherRule
import chat.homebuddy.app.R
import chat.homebuddy.core.testing.TestProfileRepository
import chat.homebuddy.core.testing.httpError
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The real ViewModel on an in-memory profile, on the plain JVM. */
class HomeSetupViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val profile = TestProfileRepository().apply { server = server.copy(households = emptyList()) }
    private val viewModel = HomeSetupViewModel(profile)
    private val elmStreet = HomeDetails(name = "Elm Street", zipCode = "80202", homeType = "condo")

    @Test
    fun `creating a home adds it to the profile`() = runTest {
        profile.refresh()

        viewModel.create(elmStreet)

        assertEquals(HomeSetupUiState(), viewModel.state.value)
        assertEquals(listOf("Elm Street"), profile.profile.first()?.households?.map { it.name })
    }

    @Test
    fun `a second tap while saving doesn't create a second home`() = runTest {
        val server = CompletableDeferred<Unit>()
        var calls = 0
        profile.whileCalling = {
            calls++
            server.await()
        }

        viewModel.create(elmStreet)
        assertTrue(viewModel.state.value.saving)
        viewModel.create(elmStreet)
        server.complete(Unit)

        assertEquals(1, calls)
        assertEquals(1, profile.server.households.size)
        assertEquals(HomeSetupUiState(), viewModel.state.value)
    }

    @Test
    fun `the server's reason is shown when it refuses the home`() = runTest {
        profile.failure = httpError(422, "Enter a 5-digit ZIP code.")

        viewModel.create(elmStreet)

        assertEquals(HomeSetupUiState(error = "Enter a 5-digit ZIP code.".asUiText()), viewModel.state.value)
    }

    @Test
    fun `being offline says so`() = runTest {
        profile.failure = IOException("No connection")

        viewModel.create(elmStreet)

        assertEquals(HomeSetupUiState(error = uiText(R.string.error_offline)), viewModel.state.value)
    }

    @Test
    fun `trying again clears the last error`() = runTest {
        profile.failure = IOException("No connection")
        viewModel.create(elmStreet)
        profile.failure = null

        viewModel.create(elmStreet)

        assertEquals(HomeSetupUiState(), viewModel.state.value)
    }
}
