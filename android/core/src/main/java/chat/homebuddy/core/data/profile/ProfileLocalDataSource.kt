package chat.homebuddy.core.data.profile

import chat.homebuddy.core.data.HouseholdEntity
import chat.homebuddy.core.data.ProfileDao
import chat.homebuddy.core.data.ProfileWithHouseholds
import chat.homebuddy.core.data.UserEntity
import kotlinx.coroutines.flow.Flow

/** The cached profile: at most one user, with their homes. */
interface ProfileLocalDataSource {
    /** Null until a profile has been saved on this phone. */
    val profile: Flow<ProfileWithHouseholds?>

    suspend fun userId(): Int?

    /** Replaces the cached user and homes together. */
    suspend fun replace(user: UserEntity, households: List<HouseholdEntity>)

    suspend fun upsertHousehold(household: HouseholdEntity)

    suspend fun clear()
}

class RoomProfileLocalDataSource(private val dao: ProfileDao) : ProfileLocalDataSource {
    override val profile: Flow<ProfileWithHouseholds?> = dao.observe()

    override suspend fun userId() = dao.userId()

    override suspend fun replace(user: UserEntity, households: List<HouseholdEntity>) = dao.replace(user, households)

    override suspend fun upsertHousehold(household: HouseholdEntity) = dao.upsertHousehold(household)

    override suspend fun clear() = dao.clear()
}
