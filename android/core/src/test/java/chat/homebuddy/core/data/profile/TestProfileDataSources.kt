package chat.homebuddy.core.data.profile

import chat.homebuddy.core.data.HouseholdEntity
import chat.homebuddy.core.data.ProfileWithHouseholds
import chat.homebuddy.core.data.UserEntity
import chat.homebuddy.core.network.HouseholdDto
import chat.homebuddy.core.network.UserDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The server's side of the account, in memory. Set [failure] to make every call throw it instead. */
class TestProfileRemoteDataSource : ProfileRemoteDataSource {
    var user = UserDto(id = 1, cognitoSub = "s", email = "pat@example.com", displayName = "Pat")
    var households = listOf(HouseholdDto(id = 7, name = "Elm Street", zipCode = "80202", homeType = "condo", role = "owner"))
    var failure: Exception? = null
    val calls = mutableListOf<String>()
    var whileCalling: suspend (String) -> Unit = {}
    var deleted = false
        private set

    override suspend fun bootstrap(idToken: String, accessToken: String) = answer("bootstrap") { user }

    override suspend fun me() = answer("me") { user }

    override suspend fun households() = answer("households") { households }

    override suspend fun createHousehold(name: String, zipCode: String, homeType: String) = answer("createHousehold") {
        val id = (households.maxOfOrNull { it.id } ?: 0) + 1
        HouseholdDto(id = id, name = name, zipCode = zipCode, homeType = homeType, role = "owner").also { households = households + it }
    }

    override suspend fun deleteAccount() = answer("deleteAccount") { deleted = true }

    private suspend fun <T> answer(operation: String, block: () -> T): T {
        calls += operation
        val result = runCatching {
            failure?.let { throw it }
            block()
        }
        whileCalling(operation)
        return result.getOrThrow()
    }
}

/** The cached profile, in memory. */
class TestProfileLocalDataSource : ProfileLocalDataSource {
    private val saved = MutableStateFlow<ProfileWithHouseholds?>(null)

    override val profile: Flow<ProfileWithHouseholds?> = saved

    override suspend fun userId() = saved.value?.user?.id

    override suspend fun replace(user: UserEntity, households: List<HouseholdEntity>) {
        saved.value = ProfileWithHouseholds(user, households)
    }

    override suspend fun upsertHousehold(household: HouseholdEntity) =
        saved.update { profile -> profile?.copy(households = profile.households.filterNot { it.id == household.id } + household) }

    override suspend fun clear() {
        saved.value = null
    }
}
