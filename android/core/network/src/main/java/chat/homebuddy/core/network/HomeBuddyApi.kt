package chat.homebuddy.core.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface HomeBuddyApi {
    /** Creates the user's profile on first sign-in. Doesn't need the Authorization header. */
    @POST("auth/bootstrap")
    suspend fun bootstrap(@Body body: BootstrapRequest): UserDto

    @GET("auth/me")
    suspend fun me(): UserDto

    @GET("households")
    suspend fun households(): List<HouseholdDto>

    @POST("households")
    suspend fun createHousehold(@Body body: HouseholdCreateRequest): HouseholdDto

    /** Public: read at startup to decide whether this app version is still supported. */
    @GET("client-config")
    suspend fun clientConfig(): ClientConfigDto
}
