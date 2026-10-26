package chat.homebuddy.core.data.profile

import chat.homebuddy.core.network.HouseholdDto
import chat.homebuddy.core.testing.httpError
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException

class CachedProfileRepositoryTest {
    private val remote = TestProfileRemoteDataSource()
    private val repository = CachedProfileRepository(remote, TestProfileLocalDataSource())

    private val elmStreet = Household(id = 7, name = "Elm Street", zipCode = "80202", homeType = "condo", role = "owner")

    private suspend fun cached() = repository.profile.first()

    @Test
    fun `nothing is cached until the profile is fetched`() = runTest {
        assertNull(cached())
    }

    @Test
    fun `signing in caches the user and their homes`() = runTest {
        repository.bootstrap(idToken = "id-1", accessToken = "access-1")

        assertEquals(Profile(User(id = 1, email = "pat@example.com", displayName = "Pat"), listOf(elmStreet)), cached())
    }

    @Test
    fun `homes are listed in the order they were created`() = runTest {
        remote.households = listOf(HouseholdDto(9, "Cabin", "80424", "other", "owner"), HouseholdDto(7, "Elm Street", "80202", "condo", "owner"))

        repository.refresh()

        assertEquals(listOf(7, 9), cached()?.households?.map { it.id })
    }

    @Test
    fun `a refresh shows changes made on another device`() = runTest {
        repository.refresh()
        remote.user = remote.user.copy(displayName = "Patricia")

        repository.refresh()

        assertEquals("Patricia", cached()?.user?.displayName)
    }

    @Test
    fun `a refresh that fails keeps the cached profile`() = runTest {
        repository.refresh()
        remote.failure = IOException("No connection")

        try {
            repository.refresh()
            fail("expected an IOException")
        } catch (_: IOException) {
        }

        assertEquals(listOf(elmStreet), cached()?.households)
    }

    @Test
    fun `a new home is added to the cached profile`() = runTest {
        repository.refresh()

        repository.createHousehold("Cabin", "80424", "other")

        assertEquals(listOf("Elm Street", "Cabin"), cached()?.households?.map { it.name })
    }

    @Test
    fun `a new home with nothing cached yet fetches the whole profile`() = runTest {
        repository.createHousehold("Cabin", "80424", "other")

        assertEquals(listOf("Elm Street", "Cabin"), cached()?.households?.map { it.name })
    }

    @Test
    fun `deleting the account passes the server's refusal on`() = runTest {
        remote.failure = httpError(502)

        try {
            repository.deleteAccount()
            fail("expected an HttpException")
        } catch (error: HttpException) {
            assertEquals(502, error.code())
        }
    }

    @Test
    fun `deleting the account asks the server`() = runTest {
        repository.deleteAccount()

        assertTrue(remote.deleted)
    }

    @Test
    fun `clearing forgets the profile`() = runTest {
        repository.refresh()

        repository.clear()

        assertNull(cached())
    }
}
