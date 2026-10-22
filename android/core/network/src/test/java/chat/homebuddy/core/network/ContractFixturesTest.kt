package chat.homebuddy.core.network

import chat.homebuddy.core.testing.ContractFixtures
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's models must decode what the backend actually sends (contracts/fixtures). */
class ContractFixturesTest {
    private fun exchange(name: String) = HomeBuddyJson.parseToJsonElement(ContractFixtures.read(name)).jsonObject

    private fun body(name: String): JsonElement = exchange(name).getValue("body")

    private fun status(name: String): Int = exchange(name).getValue("status").jsonPrimitive.int

    @Test
    fun `the signed-in user decodes`() {
        val user = HomeBuddyJson.decodeFromJsonElement(UserDto.serializer(), body("auth_me.json"))

        assertEquals(UserDto(id = 1, cognitoSub = "contract-user-sub", email = "pat@example.com", displayName = "Pat Doe"), user)
    }

    @Test
    fun `households decode, including an empty list`() {
        val households = HomeBuddyJson.decodeFromJsonElement(ListSerializer(HouseholdDto.serializer()), body("households.json"))
        val empty = HomeBuddyJson.decodeFromJsonElement(ListSerializer(HouseholdDto.serializer()), body("households_empty.json"))
        val created = HomeBuddyJson.decodeFromJsonElement(HouseholdDto.serializer(), body("household_create.json"))

        assertEquals(listOf(HouseholdDto(1, "Elm Street", "80202", "single_family", "owner")), households)
        assertTrue(empty.isEmpty())
        assertEquals(households.single(), created)
        assertEquals(201, status("household_create.json"))
    }

    @Test
    fun `client config decodes`() {
        val config = HomeBuddyJson.decodeFromJsonElement(ClientConfigDto.serializer(), body("client_config.json"))

        assertEquals("1.0.0", config.minSupported["android"])
        assertEquals(90, config.retentionDays)
    }

    @Test
    fun `a plain error message is read from detail`() {
        val error = parseApiError(status("error_conversation_busy.json"), body("error_conversation_busy.json").toString())

        assertEquals(409, error.status)
        assertNull(error.code)
        assertEquals("Still answering your last question in this conversation. Try again in a moment.", error.message)
    }

    @Test
    fun `an upload error carries a machine-readable code`() {
        val error = parseApiError(status("document_upload_not_pdf.json"), body("document_upload_not_pdf.json").toString())

        assertEquals(422, error.status)
        assertEquals("not_pdf", error.code)
        assertEquals("The uploaded file is not a PDF.", error.message)
    }

    @Test
    fun `rate limiting says how long to wait`() {
        val exchange = exchange("error_rate_limited.json")
        val retryAfter = exchange.getValue("headers").jsonObject.getValue("retry-after").jsonPrimitive.content

        val error = parseApiError(status("error_rate_limited.json"), body("error_rate_limited.json").toString(), retryAfter)

        assertEquals(429, error.status)
        assertEquals(60L, error.retryAfterSeconds)
    }

    @Test
    fun `garbage error bodies fall back to a friendly message`() {
        assertEquals("Something went wrong. Please try again.", parseApiError(502, "<html>Bad gateway</html>").message)
        assertEquals("Something went wrong. Please try again.", parseApiError(500, null).message)
    }

    @Test
    fun `every JSON fixture records a status and a request`() {
        val names = ContractFixtures.names().filter { it.endsWith(".json") }

        assertTrue("expected the backend's fixtures, found $names", names.size >= 15)
        names.forEach { name ->
            val exchange = exchange(name)
            assertTrue(name, exchange.containsKey("status") && exchange.containsKey("request"))
        }
    }
}
