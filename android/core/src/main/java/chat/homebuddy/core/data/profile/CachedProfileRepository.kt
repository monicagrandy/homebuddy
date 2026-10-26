package chat.homebuddy.core.data.profile

import chat.homebuddy.core.data.HouseholdEntity
import chat.homebuddy.core.data.ProfileWithHouseholds
import chat.homebuddy.core.data.UserEntity
import chat.homebuddy.core.network.HouseholdDto
import chat.homebuddy.core.network.UserDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [ProfileRepository] as a cache of the server's data, like the other repositories, so the app
 * opens straight to its last state and refreshes in the background.
 */
class CachedProfileRepository(
    private val remote: ProfileRemoteDataSource,
    private val local: ProfileLocalDataSource,
) : ProfileRepository {
    override val profile: Flow<Profile?> = local.profile.map { it?.toProfile() }.distinctUntilChanged()

    override suspend fun bootstrap(idToken: String, accessToken: String) {
        val user = remote.bootstrap(idToken, accessToken)
        save(user, remote.households())
    }

    override suspend fun refresh() = save(remote.me(), remote.households())

    override suspend fun createHousehold(name: String, zipCode: String, homeType: String) {
        val created = remote.createHousehold(name, zipCode, homeType)
        val userId = local.userId()
        if (userId != null) local.upsertHousehold(created.toEntity(userId)) else refresh()
    }

    override suspend fun deleteAccount() = remote.deleteAccount()

    override suspend fun clear() = local.clear()

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
