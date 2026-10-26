package chat.homebuddy.core.data.profile

import chat.homebuddy.core.data.FakeProfileDao
import chat.homebuddy.core.data.HouseholdEntity
import chat.homebuddy.core.data.ProfileWithHouseholds
import chat.homebuddy.core.data.UserEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomProfileLocalDataSourceTest {
    private val local = RoomProfileLocalDataSource(FakeProfileDao())

    private val pat = UserEntity(id = 1, email = "pat@example.com", displayName = "Pat")
    private val elmStreet = HouseholdEntity(userId = 1, id = 7, name = "Elm Street", zipCode = "80202", homeType = "condo", role = "owner")
    private val cabin = HouseholdEntity(userId = 1, id = 8, name = "Cabin", zipCode = "80424", homeType = "other", role = "owner")

    @Test
    fun `a saved profile comes back with its homes`() = runTest {
        local.replace(pat, listOf(elmStreet))

        assertEquals(ProfileWithHouseholds(pat, listOf(elmStreet)), local.profile.first())
        assertEquals(1, local.userId())
    }

    @Test
    fun `a new home is added to the saved profile`() = runTest {
        local.replace(pat, listOf(elmStreet))

        local.upsertHousehold(cabin)

        assertEquals(listOf(elmStreet, cabin), local.profile.first()?.households)
    }

    @Test
    fun `clearing forgets the profile`() = runTest {
        local.replace(pat, listOf(elmStreet))

        local.clear()

        assertNull(local.profile.first())
        assertNull(local.userId())
    }
}
