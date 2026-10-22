package chat.homebuddy.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDetailsTest {
    @Test
    fun `complete details have no problems`() {
        assertTrue(HomeDetails("Elm Street", "80202", "condo").problems().isEmpty())
    }

    @Test
    fun `each field is checked`() {
        assertEquals(
            setOf("name", "zip", "type"),
            HomeDetails(" ", "8020", "castle").problems().keys,
        )
        assertEquals(setOf("name"), HomeDetails("x".repeat(51), "80202", "condo").problems().keys)
        assertEquals(setOf("zip"), HomeDetails("Home", "8020a", "condo").problems().keys)
    }

    @Test
    fun `home types match what the backend stores`() {
        assertEquals(listOf("single_family", "townhouse", "condo", "apartment", "other"), HOME_TYPES.map { it.first })
    }
}
