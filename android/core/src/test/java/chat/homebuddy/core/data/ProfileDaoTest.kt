package chat.homebuddy.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room's queries for the cached profile, on an in-memory database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileDaoTest {
    private lateinit var database: HomeBuddyDatabase
    private lateinit var dao: ProfileDao

    private val pat = UserEntity(id = 1, email = "pat@example.com", displayName = "Pat")

    private fun home(id: Int, userId: Int = 1) =
        HouseholdEntity(userId = userId, id = id, name = "Home $id", zipCode = "80202", homeType = "condo", role = "owner")

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        dao = database.profile()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a new install has no profile`() = runTest {
        assertNull(dao.observe().first())
        assertNull(dao.userId())
    }

    @Test
    fun `the user and their homes are read together`() = runTest {
        dao.replace(pat, listOf(home(7), home(8)))

        val cached = dao.observe().first()!!

        assertEquals(pat, cached.user)
        assertEquals(setOf(home(7), home(8)), cached.households.toSet())
        assertEquals(1, dao.userId())
    }

    @Test
    fun `replacing the profile drops the previous user's homes`() = runTest {
        dao.replace(pat, listOf(home(7)))
        val sam = UserEntity(id = 2, email = "sam@example.com", displayName = null)

        dao.replace(sam, listOf(home(9, userId = 2)))

        val cached = dao.observe().first()!!
        assertEquals(sam, cached.user)
        assertEquals(listOf(home(9, userId = 2)), cached.households)
    }

    @Test
    fun `a new home joins the cached ones`() = runTest {
        dao.replace(pat, listOf(home(7)))

        dao.upsertHousehold(home(8))

        assertEquals(setOf(home(7), home(8)), dao.observe().first()!!.households.toSet())
    }

    @Test
    fun `clearing removes the user and their homes`() = runTest {
        dao.replace(pat, listOf(home(7)))

        dao.clear()

        assertNull(dao.observe().first())
        assertNull(dao.userId())
    }
}
