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
data class ClientConfigDto(
    @SerialName("min_supported") val minSupported: Map<String, String>,
    @SerialName("retention_days") val retentionDays: Int,
    val features: Map<String, Boolean> = emptyMap(),
)
