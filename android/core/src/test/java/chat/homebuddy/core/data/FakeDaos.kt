package chat.homebuddy.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

// In-memory DAOs, so the local data sources can be tested on the JVM. Room's own queries are
// tested against a real database in the …DaoTest classes.

class FakeProfileDao : ProfileDao() {
    private val user = MutableStateFlow<UserEntity?>(null)
    private val households = MutableStateFlow<List<HouseholdEntity>>(emptyList())

    override fun observe(): Flow<ProfileWithHouseholds?> = combine(user, households) { user, households ->
        user?.let { ProfileWithHouseholds(it, households.filter { home -> home.userId == it.id }) }
    }

    override suspend fun userId() = user.value?.id

    override suspend fun insertUser(user: UserEntity) {
        this.user.value = user
    }

    override suspend fun upsertHouseholds(households: List<HouseholdEntity>) = households.forEach { upsertHousehold(it) }

    override suspend fun upsertHousehold(household: HouseholdEntity) =
        households.update { homes -> homes.filterNot { it.userId == household.userId && it.id == household.id } + household }

    override suspend fun clearUsers() {
        user.value = null
    }

    override suspend fun clearHouseholds() {
        households.value = emptyList()
    }
}
