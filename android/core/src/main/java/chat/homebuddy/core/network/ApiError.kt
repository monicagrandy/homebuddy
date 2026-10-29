package chat.homebuddy.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.ResponseBody
import retrofit2.HttpException

/**
 * An error response from the API. FastAPI puts it in `detail`, which is a message, an object
 * with a machine-readable `code` (uploads: not_pdf, too_large, ...), or a list of validation errors.
 */
data class ApiError(
    val status: Int,
    val code: String?,
    val message: String,
    val retryAfterSeconds: Long? = null,
)

private const val FALLBACK_MESSAGE = "Something went wrong. Please try again."

fun parseApiError(status: Int, body: String?, retryAfter: String? = null, json: Json = HomeBuddyJson): ApiError {
    val detail = body
        ?.let { runCatching { (json.parseToJsonElement(it) as? JsonObject)?.get("detail") }.getOrNull() }
    val (code, message) = when (detail) {
        is JsonPrimitive -> null to (detail.textOrNull() ?: FALLBACK_MESSAGE)
        is JsonObject -> detail["code"].textOrNull() to (detail["message"].textOrNull() ?: FALLBACK_MESSAGE)
        is JsonArray -> "invalid" to FALLBACK_MESSAGE
        else -> null to FALLBACK_MESSAGE
    }
    return ApiError(status = status, code = code, message = message, retryAfterSeconds = retryAfter?.toLongOrNull())
}

/** Structured values, JSON null and non-text primitives are not user-facing messages or codes. */
private fun JsonElement?.textOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

/** An unavailable error body must not hide the HTTP status or break an error handler. */
internal fun ResponseBody?.readErrorBody(): String? = runCatching { this?.string() }.getOrNull()

fun HttpException.toApiError(): ApiError {
    val raw = response()
    return parseApiError(code(), raw?.errorBody().readErrorBody(), raw?.headers()?.get("Retry-After"))
}
