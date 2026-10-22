package chat.homebuddy.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors of backend/schemas.py. contracts/fixtures holds real responses; ContractFixturesTest decodes them.

@Serializable
data class UserDto(
    val id: Int,
    @SerialName("cognito_sub") val cognitoSub: String,
    val email: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class HouseholdDto(
    val id: Int,
    val name: String,
    @SerialName("zip_code") val zipCode: String,
    @SerialName("home_type") val homeType: String,
    val role: String,
)

@Serializable
data class BootstrapRequest(
    @SerialName("id_token") val idToken: String,
    @SerialName("access_token") val accessToken: String,
)

@Serializable
data class HouseholdCreateRequest(
    val name: String,
    @SerialName("zip_code") val zipCode: String,
    @SerialName("home_type") val homeType: String,
)

@Serializable
data class DocumentDto(
    val id: Int,
    @SerialName("entry_id") val entryId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("doc_type") val docType: String,
    @SerialName("uploaded_at") val uploadedAt: String? = null,
    /** "indexing" while the server builds its search index, then "ready". */
    val status: String = "ready",
)

@Serializable
data class DocumentJobDto(
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("entry_id") val entryId: String,
    /** queued | running | succeeded | failed, or duplicate when nothing was queued. */
    val status: String,
    @SerialName("duplicate_of") val duplicateOf: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val error: String? = null,
    @SerialName("chunks_indexed") val chunksIndexed: Int? = null,
    val attempts: Int = 0,
)
