package chat.homebuddy.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room's queries for cached documents and uploads, on an in-memory database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentDaoTest {
    private lateinit var database: HomeBuddyDatabase
    private lateinit var documents: DocumentDao
    private lateinit var uploads: UploadDao

    private fun document(entryId: String, name: String = entryId, householdId: Int = 7) =
        DocumentEntity(householdId, entryId, name, "manual", "ready", null, null)

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        documents = database.documents()
        uploads = database.uploads()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a home's documents are listed by name, ignoring case`() = runTest {
        documents.upsertAll(listOf(document("ge", "GE washer"), document("apple", "apple tv"), document("bosch", "Bosch dishwasher")))

        assertEquals(listOf("apple", "bosch", "ge"), documents.observe(7).first().map { it.entryId })
    }

    @Test
    fun `replacing one home's documents leaves other homes alone`() = runTest {
        documents.upsertAll(listOf(document("old"), document("cabin-manual", householdId = 8)))

        documents.replaceAll(7, listOf(document("bosch")))

        assertEquals(listOf("bosch"), documents.observe(7).first().map { it.entryId })
        assertEquals(listOf("cabin-manual"), documents.observe(8).first().map { it.entryId })
    }

    @Test
    fun `deleting removes only that document`() = runTest {
        documents.upsertAll(listOf(document("bosch"), document("ge")))

        documents.delete(7, "bosch")

        assertEquals(listOf("ge"), documents.observe(7).first().map { it.entryId })
    }

    @Test
    fun `uploads are listed newest first, per home`() = runTest {
        uploads.upsert(upload("older").copy(createdAt = 1))
        uploads.upsert(upload("newer").copy(createdAt = 2))
        uploads.upsert(upload("cabin").copy(householdId = 8))

        assertEquals(listOf("newer", "older"), uploads.observe(7).first().map { it.id })
    }

    @Test
    fun `saving an upload again updates it`() = runTest {
        uploads.upsert(upload())

        uploads.upsert(upload(state = UploadState.INDEXING, jobId = "job-1"))

        assertEquals(UploadState.INDEXING, uploads.get("upload-1")?.state)
        assertEquals(1, uploads.all().size)
    }

    @Test
    fun `deleting and clearing`() = runTest {
        documents.upsertAll(listOf(document("bosch")))
        uploads.upsert(upload("a"))
        uploads.upsert(upload("b"))

        uploads.delete("a")
        assertNull(uploads.get("a"))

        uploads.clear()
        documents.clear()
        assertTrue(uploads.all().isEmpty())
        assertTrue(documents.observe(7).first().isEmpty())
    }
}
