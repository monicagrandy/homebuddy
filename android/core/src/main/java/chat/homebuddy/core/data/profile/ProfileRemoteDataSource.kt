package chat.homebuddy.core.data.profile

import chat.homebuddy.core.network.BootstrapRequest
import chat.homebuddy.core.network.HomeBuddyApi
import chat.homebuddy.core.network.HouseholdCreateRequest
import chat.homebuddy.core.network.HouseholdDto
import chat.homebuddy.core.network.UserDto
import java.io.IOException
import retrofit2.HttpException

/** The account on the server. Failures throw [HttpException], or [IOException] offline. */
interface ProfileRemoteDataSource {
    /** Creates the user the first time this account signs in (or finds them). */
    suspend fun bootstrap(idToken: String, accessToken: String): UserDto

    suspend fun me(): UserDto

    suspend fun households(): List<HouseholdDto>

    suspend fun createHousehold(name: String, zipCode: String, homeType: String): HouseholdDto

    /** A 502 means the account's data is gone but closing its sign-in failed (safe to retry). */
    suspend fun deleteAccount()
}

class RetrofitProfileRemoteDataSource(private val api: HomeBuddyApi) : ProfileRemoteDataSource {
    override suspend fun bootstrap(idToken: String, accessToken: String) =
        api.bootstrap(BootstrapRequest(idToken = idToken, accessToken = accessToken))

    override suspend fun me() = api.me()

    override suspend fun households() = api.households()

    override suspend fun createHousehold(name: String, zipCode: String, homeType: String) =
        api.createHousehold(HouseholdCreateRequest(name = name, zipCode = zipCode, homeType = homeType))

    override suspend fun deleteAccount() {
        val response = api.deleteAccount()
        if (!response.isSuccessful) throw HttpException(response)
    }
}
