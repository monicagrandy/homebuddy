package chat.homebuddy.feature.documents

import chat.homebuddy.app.R
import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.UploadEntity
import chat.homebuddy.core.data.UploadState
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentsUiStateTest {
    private fun upload(state: UploadState, url: String? = null, errorCode: String? = null, errorMessage: String? = null, entryId: String? = null) =
        UploadEntity("u", 7, "Bosch manual", "manual", null, url, state, entryId = entryId, errorCode = errorCode, errorMessage = errorMessage, createdAt = 1)

    private fun document(entryId: String, status: String = "ready") = DocumentEntity(7, entryId, entryId, "manual", status, null, null)

    @Test
    fun `each upload state reads as a status`() {
        assertEquals(UploadStatus.WAITING, upload(UploadState.QUEUED).toItem().status)
        assertEquals(UploadStatus.UPLOADING, upload(UploadState.UPLOADING).toItem().status)
        assertEquals(UploadStatus.READING, upload(UploadState.INDEXING).toItem().status)
        assertEquals(uiText(R.string.documents_duplicate), upload(UploadState.DUPLICATE).toItem().message)
    }

    @Test
    fun `a link that was a web page offers to open it instead of retrying`() {
        val item = upload(UploadState.FAILED, url = "https://example.com/support", errorCode = "not_pdf").toItem()

        assertEquals(uiText(R.string.documents_not_a_pdf_link), item.message)
        assertEquals("https://example.com/support", item.openUrl)
        assertFalse(item.canRetry)
    }

    @Test
    fun `a link that isn't a web page is never offered to open`() {
        val item = upload(UploadState.FAILED, url = "intent://scan/#Intent;end", errorCode = "not_pdf").toItem()

        assertNull(item.openUrl)
    }

    @Test
    fun `other failures show the server's explanation`() {
        val item = upload(UploadState.FAILED, errorCode = "no_text", errorMessage = "This PDF has no readable text (it may be a scan).").toItem()

        assertEquals("This PDF has no readable text (it may be a scan).".asUiText(), item.message)
        assertNull(item.openUrl)
        assertFalse(item.canRetry)
    }

    @Test
    fun `only recoverable failures offer retry`() {
        assertTrue(upload(UploadState.FAILED, url = "https://example.com/manual.pdf", errorCode = "network").toItem().canRetry)
        assertTrue(upload(UploadState.FAILED, url = "https://example.com/manual.pdf", errorCode = "index_failed").toItem().canRetry)
        assertTrue(upload(UploadState.FAILED, url = "https://example.com/manual.pdf", errorCode = "download_failed").toItem().canRetry)
        listOf("not_pdf", "too_large", "empty", "invalid_url", "invalid", "no_text", "deleted", "rejected").forEach { code ->
            assertFalse(code, upload(UploadState.FAILED, url = "https://example.com/manual.pdf", errorCode = code).toItem().canRetry)
        }
        assertFalse(upload(UploadState.FAILED, errorCode = "index_failed").toItem().canRetry)
        assertFalse(upload(UploadState.INDEXING, url = "https://example.com/manual.pdf").toItem().canRetry)
    }

    @Test
    fun `a document still being read is shown once, as the upload`() {
        val state = documentsUiState(
            documents = listOf(document("bosch-manual", status = "indexing"), document("ge-washer")),
            uploads = listOf(upload(UploadState.INDEXING, entryId = "bosch-manual")),
            refreshing = false,
            message = null,
        )

        assertEquals(listOf("ge-washer"), state.documents.map { it.entryId })
        assertEquals(1, state.uploads.size)
    }

    @Test
    fun `an indexing document from another device is marked as indexing`() {
        val state = documentsUiState(listOf(document("bosch-manual", status = "indexing")), emptyList(), false, null)

        assertTrue(state.documents.single().indexing)
    }

    @Test
    fun `a completed upload pending cleanup does not duplicate its saved document`() {
        val state = documentsUiState(
            listOf(document("bosch-manual")), listOf(upload(UploadState.COMPLETED, entryId = "bosch-manual")), false, null,
        )
        assertEquals(1, state.documents.size)
        assertTrue(state.uploads.isEmpty())
    }

    @Test
    fun `empty only once loaded`() {
        assertTrue(documentsUiState(emptyList(), emptyList(), false, null).isEmpty)
        assertTrue(!DocumentsUiState().isEmpty)
    }
}
