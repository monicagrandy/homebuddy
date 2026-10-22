package chat.homebuddy.core.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

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

    @GET("documents")
    suspend fun documents(@Query("household_id") householdId: Int): List<DocumentDto>

    /** 204 when deleted; 404 when it's already gone (for example deleted on another device). */
    @DELETE("documents/{entryId}")
    suspend fun deleteDocument(@Path("entryId") entryId: String, @Query("household_id") householdId: Int): Response<Unit>

    /**
     * Stores a PDF ([file]) or link ([url]) and indexes it in the background: 202 with a queued job,
     * or 200 when [idempotencyKey] was already used (the same job) or the document is a duplicate.
     */
    @Multipart
    @POST("documents/jobs")
    suspend fun createDocumentJob(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Part("household_id") householdId: RequestBody,
        @Part("display_name") displayName: RequestBody,
        @Part("doc_type") docType: RequestBody,
        @Part file: MultipartBody.Part?,
        @Part("url") url: RequestBody?,
    ): DocumentJobDto

    @GET("documents/jobs/{jobId}")
    suspend fun documentJob(@Path("jobId") jobId: String): DocumentJobDto

    /** The signed-in user's threads, newest first. [updatedSince] (ISO 8601) fetches only changes. */
    @GET("conversations")
    suspend fun conversations(
        @Query("household_id") householdId: Int,
        @Query("updated_since") updatedSince: String? = null,
        @Query("limit") limit: Int = 100,
    ): List<ConversationDto>

    @GET("conversations/{sessionId}/messages")
    suspend fun conversationMessages(@Path("sessionId") sessionId: String, @Query("household_id") householdId: Int): List<MessageDto>

    /** 204 when deleted; 404 when it's already gone. */
    @DELETE("conversations/{sessionId}")
    suspend fun deleteConversation(@Path("sessionId") sessionId: String, @Query("household_id") householdId: Int): Response<Unit>

    /**
     * Deletes the account: its data, then its Cognito sign-in. 204 when done; 502 when the data is
     * gone but closing the sign-in failed (safe to retry).
     */
    @DELETE("me")
    suspend fun deleteAccount(): Response<Unit>
}
