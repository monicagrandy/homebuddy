package chat.homebuddy.core.data

import chat.homebuddy.core.network.BootstrapRequest
import chat.homebuddy.core.network.HomeBuddyApi
import chat.homebuddy.core.network.HouseholdCreateRequest
import chat.homebuddy.core.network.HouseholdDto
import chat.homebuddy.core.network.UserDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import retrofit2.Response

data class User(val id: Int, val email: String, val displayName: String?)

data class Household(val id: Int, val name: String, val zipCode: String, val homeType: String, val role: String)

data class Profile(val user: User, val households: List<Household>)

/**
 * The signed-in user and their homes. Like the other repositories it's a cache of the server's
 * data in Room, so the app opens straight to its last state and refreshes in the background.
 */
class ProfileRepository(private val api: HomeBuddyApi, private val dao: ProfileDao) {
    /** Null until the profile has been fetched once on this phone. */
    val profile: Flow<Profile?> = dao.observe().map { it?.toProfile() }.distinctUntilChanged()

    /** After a sign-in: creates the user on the server the first time (or finds them), then caches the profile. */
    suspend fun bootstrap(idToken: String, accessToken: String) {
        val user = api.bootstrap(BootstrapRequest(idToken = idToken, accessToken = accessToken))
        save(user, api.households())
    }

    suspend fun refresh() = save(api.me(), api.households())

    suspend fun createHousehold(name: String, zipCode: String, homeType: String) {
        val created = api.createHousehold(HouseholdCreateRequest(name = name, zipCode = zipCode, homeType = homeType))
        val userId = dao.userId()
        if (userId != null) dao.upsertHousehold(created.toEntity(userId)) else refresh()
    }

    /** 204 when the account is gone; 502 when its data is gone but closing the sign-in failed (safe to retry). */
    suspend fun deleteAccount(): Response<Unit> = api.deleteAccount()

    suspend fun clear() = dao.clear()

    private suspend fun save(user: UserDto, households: List<HouseholdDto>) {
        dao.replace(UserEntity(id = user.id, email = user.email, displayName = user.displayName), households.map { it.toEntity(user.id) })
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
