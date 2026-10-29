package chat.homebuddy.core.data.profile

import chat.homebuddy.core.auth.AccessTokenCache
import chat.homebuddy.core.data.HouseholdEntity
import chat.homebuddy.core.data.ProfileWithHouseholds
import chat.homebuddy.core.data.UserEntity
import chat.homebuddy.core.network.HouseholdDto
import chat.homebuddy.core.network.UserDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [ProfileRepository] as a cache of the server's data, like the other repositories, so the app
 * opens straight to its last state and refreshes in the background.
 */
class CachedProfileRepository(
    private val remote: ProfileRemoteDataSource,
    private val local: ProfileLocalDataSource,
    private val sessions: AccessTokenCache,
) : ProfileRepository {
    private val updating = Mutex()
    private var generation = Any()
    private val requests = mutableSetOf<Job>()

    override val profile: Flow<Profile?> = local.profile.map { it?.toProfile() }.distinctUntilChanged()

    override suspend fun bootstrap(idToken: String, accessToken: String) =
        withProfile(accessToken, startingSignIn = true) { session, generation ->
            val user = session.request { remote.bootstrap(idToken, accessToken) }
            val households = session.request { remote.households() }
            write(session, generation) { save(user, households) }
        }

    override suspend fun refresh() = withProfile { session, generation ->
        refresh(session, generation)
    }

    private suspend fun refresh(session: AccessTokenCache.SessionWork, generation: Any) {
        val user = session.request { remote.me() }
        val households = session.request { remote.households() }
        write(session, generation) { save(user, households) }
    }

    override suspend fun createHousehold(name: String, zipCode: String, homeType: String) = withProfile { session, generation ->
        val created = session.request { remote.createHousehold(name, zipCode, homeType) }
        val saved = write(session, generation) {
            val userId = local.userId()
            if (userId != null) local.upsertHousehold(created.toEntity(userId))
            userId != null
        }
        if (!saved) refresh(session, generation)
    }

    override suspend fun deleteAccount() = withProfile { session, _ -> session.request { remote.deleteAccount() } }

    override suspend fun clear() = updating.withLock {
        invalidate()
        local.clear()
    }

    private fun invalidate() {
        generation = Any()
        requests.forEach { it.cancel(CancellationException("The cached profile's session changed.")) }
    }

    private suspend fun <T> withProfile(
        accessToken: String? = null,
        startingSignIn: Boolean = false,
        block: suspend (AccessTokenCache.SessionWork, Any) -> T,
    ): T = sessions.withSession(accessToken) { session ->
        coroutineScope {
            val job = currentCoroutineContext().job
            val owner = session.commit {
                updating.withLock {
                    if (startingSignIn) invalidate()
                    requests += job
                    generation
                }
            }
            try {
                block(session, owner)
            } finally {
                withContext(NonCancellable) { updating.withLock { requests -= job } }
            }
        }
    }

    private suspend fun <T> write(session: AccessTokenCache.SessionWork, owner: Any, block: suspend () -> T): T = session.commit {
        updating.withLock {
            currentCoroutineContext().ensureActive()
            if (generation !== owner) throw CancellationException("The cached profile's session changed.")
            block()
        }
    }

    private suspend fun save(user: UserDto, households: List<HouseholdDto>) {
        local.replace(UserEntity(id = user.id, email = user.email, displayName = user.displayName), households.map { it.toEntity(user.id) })
    }
}

private fun HouseholdDto.toEntity(userId: Int) =
    HouseholdEntity(userId = userId, id = id, name = name, zipCode = zipCode, homeType = homeType, role = role)

private fun ProfileWithHouseholds.toProfile() = Profile(
    user = User(id = user.id, email = user.email, displayName = user.displayName),
    households = households
        .sortedBy { it.id }
        .map { Household(id = it.id, name = it.name, zipCode = it.zipCode, homeType = it.homeType, role = it.role) },
)
