package chat.homebuddy.core.data

import kotlinx.coroutines.flow.Flow
import retrofit2.Response

data class User(val id: Int, val email: String, val displayName: String?)

data class Household(val id: Int, val name: String, val zipCode: String, val homeType: String, val role: String)

data class Profile(val user: User, val households: List<Household>)

/** The signed-in user and their homes. */
interface ProfileRepository {
    /** Null until the profile has been fetched once on this phone. */
    val profile: Flow<Profile?>

    /** After a sign-in: creates the user on the server the first time (or finds them), then caches the profile. */
    suspend fun bootstrap(idToken: String, accessToken: String)

    suspend fun refresh()

    suspend fun createHousehold(name: String, zipCode: String, homeType: String)

    /** 204 when the account is gone; 502 when its data is gone but closing the sign-in failed (safe to retry). */
    suspend fun deleteAccount(): Response<Unit>

    suspend fun clear()
}
