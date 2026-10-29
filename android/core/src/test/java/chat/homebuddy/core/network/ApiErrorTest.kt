package chat.homebuddy.core.network

import java.io.IOException
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiErrorTest {
    private val fallback = "Something went wrong. Please try again."

    @Test
    fun `arrays and objects in code or message are ignored independently`() {
        val details = listOf(
            """{"code": [], "message": "Readable"}""" to ApiError(422, null, "Readable"),
            """{"code": {}, "message": "Readable"}""" to ApiError(422, null, "Readable"),
            """{"code": "not_pdf", "message": []}""" to ApiError(422, "not_pdf", fallback),
            """{"code": "not_pdf", "message": {}}""" to ApiError(422, "not_pdf", fallback),
            """{"code": [{"nested": true}], "message": {"nested": []}}""" to ApiError(422, null, fallback),
        )

        details.forEach { (detail, expected) -> assertEquals(detail, expected, parseApiError(422, """{"detail": $detail}""")) }
    }

    @Test
    fun `all unexpected JSON roots and detail types fall back safely`() {
        val roots = listOf("null", "[]", "[{}]", "true", "42", "\"unexpected\"", "{}")
        val details = listOf("null", "true", "false", "42", "{}")

        (roots + details.map { """{"detail": $it}""" }).forEach { body ->
            assertEquals(body, ApiError(500, null, fallback), parseApiError(500, body))
        }
    }

    @Test
    fun `non-text and blank fields do not become display text or codes`() {
        listOf("null", "true", "42", "\"\"", "\"  \"").forEach { value ->
            assertEquals(ApiError(500, null, fallback), parseApiError(500, """{"detail": {"code": $value, "message": $value}}"""))
        }
    }

    @Test
    fun `malformed truncated and non-JSON bodies use a friendly fallback`() {
        val bodies = listOf(
            null, "", "<html>Bad gateway</html>", "{",
            """{"detail":"unfinished""",
            """{"detail":{"message":"cut""",
        )
        bodies.forEach { body ->
            assertEquals(body, ApiError(502, null, fallback, 60), parseApiError(502, body, retryAfter = "60"))
        }
    }

    @Test
    fun `supported error formats preserve their details and retry interval`() {
        assertEquals(ApiError(409, null, "Busy"), parseApiError(409, """{"detail":"Busy"}"""))
        assertEquals(ApiError(422, "invalid", fallback), parseApiError(422, """{"detail":[{"msg":"required"}]}"""))
        assertEquals(ApiError(429, "limited", "Wait", 30), parseApiError(429, """{"detail":{"code":"limited","message":"Wait"}}""", "30"))
        assertNull(parseApiError(429, null, "not a number").retryAfterSeconds)
        assertNull(parseApiError(429, null, "9223372036854775808").retryAfterSeconds)
    }

    @Test
    fun `an unreadable Retrofit error body does not break the error handler`() {
        listOf(IOException("truncated body"), IllegalStateException("closed body")).forEach { failure ->
            val body = object : ResponseBody() {
                override fun contentType(): MediaType = "application/json".toMediaType()
                override fun contentLength(): Long = -1
                override fun source(): BufferedSource = throw failure
            }
            val error = HttpException(Response.error<Unit>(503, body))

            assertEquals(ApiError(503, null, fallback), error.toApiError())
        }
    }

    @Test
    fun `reading an already consumed Retrofit error body is safe`() {
        val body = """{"detail":"Busy"}""".toResponseBody("application/json".toMediaType())
        val error = HttpException(Response.error<Unit>(503, body))
        assertEquals("Busy", error.toApiError().message)

        assertEquals(ApiError(503, null, fallback), error.toApiError())
    }
}
