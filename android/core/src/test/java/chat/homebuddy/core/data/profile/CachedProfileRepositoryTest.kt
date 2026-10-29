package chat.homebuddy.core.data.profile

import chat.homebuddy.core.auth.AccessTokenCache
import chat.homebuddy.core.auth.TokenSet
import chat.homebuddy.core.data.HouseholdEntity
import chat.homebuddy.core.data.UserEntity
import chat.homebuddy.core.network.HouseholdDto
import chat.homebuddy.core.network.UserDto
import chat.homebuddy.core.testing.httpError
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException

class CachedProfileRepositoryTest {
    private val remote = TestProfileRemoteDataSource()
    private val sessions = AccessTokenCache()
    private val local = TestProfileLocalDataSource()
    private val repository = CachedProfileRepository(remote, local, sessions)

    private val elmStreet = Household(id = 7, name = "Elm Street", zipCode = "80202", homeType = "condo", role = "owner")

    private suspend fun cached() = repository.profile.first()

    @Test
    fun `nothing is cached until the profile is fetched`() = runTest {
        assertNull(cached())
    }

    @Test
    fun `signing in caches the user and their homes`() = runTest {
        sessions.put(tokens("1"))
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

    @Test
    fun `logout cancels an in-flight profile request without waiting for its response`() = runTest {
        repository.refresh()
        remote.calls.clear()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        remote.whileCalling = { operation ->
            if (operation == "me") {
                started.complete(Unit)
                try {
                    response.await()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val result = async { runCatching { repository.refresh() } }
        started.await()

        sessions.clear()
        repository.clear()

        cancelled.await()
        assertTrue(result.await().exceptionOrNull() is CancellationException)
        assertEquals(listOf("me"), remote.calls)
        assertNull(cached())
        assertTrue("the network response was never released", !response.isCompleted)
    }

    @Test
    fun `an old user response cannot fetch another account's households`() = runTest {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        remote.whileCalling = { operation ->
            if (operation == "me") {
                started.complete(Unit)
                withContext(NonCancellable) { response.await() }
            }
        }
        val result = async { runCatching { repository.refresh() } }
        started.await()

        sessions.clear()
        repository.clear()
        signInAnotherAccount()
        response.complete(Unit)

        assertTrue(result.await().exceptionOrNull() is CancellationException)
        assertEquals(2, cached()?.user?.id)
        assertEquals(listOf(20), cached()?.households?.map { it.id })
        assertEquals(1, remote.calls.count { it == "households" })
    }

    @Test
    fun `an old household response cannot overwrite the next account's profile`() = runTest {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var holdOldResponse = true
        remote.whileCalling = { operation ->
            if (operation == "households" && holdOldResponse) {
                holdOldResponse = false
                started.complete(Unit)
                withContext(NonCancellable) { response.await() }
            }
        }
        val result = async { runCatching { repository.refresh() } }
        started.await()

        sessions.clear()
        repository.clear()
        signInAnotherAccount()
        response.complete(Unit)

        assertTrue(result.await().exceptionOrNull() is CancellationException)
        assertEquals(2, cached()?.user?.id)
        assertEquals(listOf(20), cached()?.households?.map { it.id })
    }

    @Test
    fun `clearing the profile rejects a late response even if auth has not changed`() = runTest {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        remote.whileCalling = {
            if (it == "households") {
                started.complete(Unit)
                withContext(NonCancellable) { response.await() }
            }
        }
        val result = async { runCatching { repository.refresh() } }
        started.await()

        repository.clear()
        response.complete(Unit)

        assertTrue(result.await().exceptionOrNull() is CancellationException)
        assertNull(cached())
    }

    @Test
    fun `a late household creation cannot attach an old account's home to the new account`() = runTest {
        repository.refresh()
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        remote.whileCalling = {
            if (it == "createHousehold") {
                started.complete(Unit)
                withContext(NonCancellable) { response.await() }
            }
        }
        val result = async { runCatching { repository.createHousehold("Old cabin", "80424", "other") } }
        started.await()

        sessions.clear()
        repository.clear()
        signInAnotherAccount()
        response.complete(Unit)

        assertTrue(result.await().exceptionOrNull() is CancellationException)
        assertEquals(2, cached()?.user?.id)
        assertEquals(listOf("New home"), cached()?.households?.map { it.name })
    }

    @Test
    fun `a superseded bootstrap cannot start work in the new account's session`() = runTest {
        signInAnotherAccount()

        val result = runCatching { repository.bootstrap("old-id", "old-access") }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(listOf("bootstrap", "households"), remote.calls)
        assertEquals(2, cached()?.user?.id)
    }

    @Test
    fun `clearing waits for an already started local write and then removes it`() = runTest {
        val writing = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val slowLocal = object : ProfileLocalDataSource by local {
            override suspend fun replace(user: UserEntity, households: List<HouseholdEntity>) {
                writing.complete(Unit)
                withContext(NonCancellable) { response.await() }
                local.replace(user, households)
            }
        }
        val repository = CachedProfileRepository(remote, slowLocal, sessions)
        val refresh = async { runCatching { repository.refresh() } }
        writing.await()

        val cleared = async(start = CoroutineStart.UNDISPATCHED) { repository.clear() }
        assertTrue("clear must wait until the write is finished", !cleared.isCompleted)
        response.complete(Unit)
        refresh.await()
        cleared.await()

        assertNull(cached())
    }

    private suspend fun signInAnotherAccount() {
        sessions.put(tokens("2"))
        remote.user = UserDto(2, "another-sub", "new@example.com", "New account")
        remote.households = listOf(HouseholdDto(20, "New home", "10001", "condo", "owner"))
        repository.bootstrap("id-2", "access-2")
    }

    private fun tokens(account: String) = TokenSet("access-$account", "id-$account", "refresh-$account", null)
}
