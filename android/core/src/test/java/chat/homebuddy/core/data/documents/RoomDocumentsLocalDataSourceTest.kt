package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.FakeDocumentDao
import chat.homebuddy.core.data.FakeUploadDao
import chat.homebuddy.core.data.UploadState
import chat.homebuddy.core.data.upload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDocumentsLocalDataSourceTest {
    private val local = RoomDocumentsLocalDataSource(FakeDocumentDao(), FakeUploadDao())

    private fun document(entryId: String) = DocumentEntity(7, entryId, entryId, "manual", "ready", null, null)

    @Test
    fun `a home's documents are replaced and deleted`() = runTest {
        local.replaceDocuments(7, listOf(document("bosch"), document("ge")))
        local.deleteDocument(7, "bosch")

        assertEquals(listOf(document("ge")), local.documents(7).first())
    }

    @Test
    fun `uploads are saved, read back and deleted`() = runTest {
        local.saveUpload(upload())
        local.saveUpload(upload(state = UploadState.INDEXING, jobId = "job-1"))

        assertEquals(UploadState.INDEXING, local.upload("upload-1")?.state)
        assertEquals(listOf("upload-1"), local.uploads(7).first().map { it.id })

        local.deleteUpload("upload-1")
        assertNull(local.upload("upload-1"))
    }

    @Test
    fun `clearing removes documents and uploads`() = runTest {
        local.replaceDocuments(7, listOf(document("bosch")))
        local.saveUpload(upload())

        local.clear()

        assertTrue(local.documents(7).first().isEmpty())
        assertTrue(local.uploads(7).first().isEmpty())
    }
}
