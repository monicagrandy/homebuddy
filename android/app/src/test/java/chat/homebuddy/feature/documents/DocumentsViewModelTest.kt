package chat.homebuddy.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import chat.homebuddy.app.MainDispatcherRule
import chat.homebuddy.app.R
import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.documents.UploadFiles
import chat.homebuddy.core.domain.ObserveCurrentHouseholdUseCase
import chat.homebuddy.core.testing.TestDocumentsRepository
import chat.homebuddy.core.testing.TestProfileRepository
import chat.homebuddy.core.testing.httpError
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import java.io.IOException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The real ViewModel on in-memory test repositories, on the plain JVM. */
class DocumentsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val profile = TestProfileRepository()
    private val repository = TestDocumentsRepository().apply {
        server = listOf(document("bosch-manual", "Bosch manual"), document("ge-washer", "GE washer"))
    }
    private val manual = "content://downloads/bosch-manual"

    private fun document(entryId: String, name: String) = DocumentEntity(7, entryId, name, "manual", "ready", null, null)

    private val link = parseManualLink("https://example.com/manual.pdf") as ManualLink.Valid

    /** The documents tab, with its state collected as the screen would; [share] is what another app shared to it. */
    private fun TestScope.documents(share: SharedDocument? = null, saved: SavedStateHandle = SavedStateHandle()) =
        DocumentsViewModel(share, saved, ObserveCurrentHouseholdUseCase(profile), repository)
            .also { viewModel -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} } }

    /** The saved state as it comes back after process death: only what was in the bundle. */
    private fun SavedStateHandle.afterProcessDeath() = SavedStateHandle(keys().associateWith { get<Any?>(it) })

    @Test
    fun `a home's documents and uploads are listed`() = runTest {
        profile.refresh()
        val viewModel = documents()

        viewModel.refresh()
        repository.addLink(7, "Warranty", "https://example.com/warranty.pdf")

        val state = viewModel.state.value
        assertEquals(listOf("Bosch manual", "GE washer"), state.documents.map { it.name })
        assertEquals(listOf("Warranty"), state.uploads.map { it.name })
        assertTrue(state.loaded)
        assertFalse(state.refreshing)
    }

    @Test
    fun `a refresh at launch waits for the cached profile`() = runTest {
        val viewModel = documents()

        viewModel.refresh()
        assertTrue(repository.refreshed.isEmpty())
        profile.refresh()

        assertEquals(listOf(7), repository.refreshed)
        assertEquals(2, viewModel.state.value.documents.size)
    }

    @Test
    fun `a refresh while offline keeps the list without a message`() = runTest {
        profile.refresh()
        val viewModel = documents()
        repository.failure = IOException("No connection")

        viewModel.refresh()

        assertNull(viewModel.state.value.message)
        assertFalse(viewModel.state.value.refreshing)
    }

    @Test
    fun `a refresh the server refuses shows its reason, until it's been shown`() = runTest {
        profile.refresh()
        val viewModel = documents()
        repository.failure = httpError(500, "Something went wrong.")

        viewModel.refresh()
        assertEquals("Something went wrong.".asUiText(), viewModel.state.value.message)

        viewModel.messageShown()
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `a picked file is named after its file name`() = runTest {
        profile.refresh()
        val viewModel = documents()
        repository.fileNames[manual] = "Bosch_SHX3AR75UC-manual.pdf"

        viewModel.onFilePicked(manual)
        assertEquals(PickedFile(manual, "Bosch SHX3AR75UC manual"), viewModel.state.value.picked)

        viewModel.onFilePicked("content://unnamed/1")
        assertNull(viewModel.state.value.picked?.suggestedName)
    }

    @Test
    fun `naming a picked file uploads it`() = runTest {
        profile.refresh()
        val viewModel = documents()
        viewModel.onFilePicked(manual)

        viewModel.addPickedFile("Bosch manual")

        assertNull(viewModel.state.value.picked)
        val upload = repository.uploads(7).first().single()
        assertEquals("Bosch manual", upload.displayName)
        assertEquals(manual, upload.filePath)
    }

    @Test
    fun `a picked file that's too large, can't be read or can't be stored says so`() = runTest {
        profile.refresh()
        val viewModel = documents()

        repository.fileFailure = UploadFiles.FileTooLarge()
        viewModel.onFilePicked(manual)
        viewModel.addPickedFile("Bosch manual")
        assertEquals(uiText(R.string.documents_too_large), viewModel.state.value.message)

        repository.fileFailure = IOException("No permission to read it.")
        viewModel.onFilePicked(manual)
        viewModel.addPickedFile("Bosch manual")
        assertEquals(uiText(R.string.documents_unreadable), viewModel.state.value.message)

        repository.fileFailure = UploadFiles.CopyFailed(IOException("ENOSPC (No space left on device)"))
        viewModel.onFilePicked(manual)
        viewModel.addPickedFile("Bosch manual")
        assertEquals(uiText(R.string.documents_copy_failed), viewModel.state.value.message)
    }

    @Test
    fun `cancelling any step of adding adds nothing`() = runTest {
        profile.refresh()
        val viewModel = documents()

        viewModel.onFilePicked(manual)
        viewModel.cancelAdding()
        assertNull(viewModel.state.value.picked)

        viewModel.onPasteLink()
        viewModel.cancelAdding()
        assertFalse(viewModel.state.value.pastingLink)

        viewModel.onLinkChosen(link)
        viewModel.cancelAdding()
        assertNull(viewModel.state.value.pendingLink)

        assertTrue(repository.uploads(7).first().isEmpty())
    }

    @Test
    fun `a link is queued with its name`() = runTest {
        profile.refresh()
        val viewModel = documents()

        viewModel.onLinkChosen(link)
        assertEquals(link, viewModel.state.value.pendingLink)
        viewModel.addPendingLink("Bosch manual")

        assertNull(viewModel.state.value.pendingLink)
        assertEquals("Bosch manual", viewModel.state.value.uploads.single().name)
        assertEquals("https://example.com/manual.pdf", repository.uploads(7).first().single().url)
    }

    @Test
    fun `a pasted link survives process death, while pasting and while naming it`() = runTest {
        profile.refresh()
        val pasting = SavedStateHandle()
        documents(saved = pasting).onPasteLink()

        val naming = pasting.afterProcessDeath()
        val restoredWhilePasting = documents(saved = naming)
        assertTrue(restoredWhilePasting.state.value.pastingLink)
        restoredWhilePasting.onLinkChosen(link)

        val restoredWhileNaming = documents(saved = naming.afterProcessDeath())
        assertFalse(restoredWhileNaming.state.value.pastingLink)
        assertEquals(link, restoredWhileNaming.state.value.pendingLink)
        restoredWhileNaming.addPendingLink("Bosch manual")
        assertEquals("https://example.com/manual.pdf", repository.uploads(7).first().single().url)
    }

    @Test
    fun `a shared link is asked about once, across process death`() = runTest {
        profile.refresh()
        val share = SharedDocument.Link(link)
        val saved = SavedStateHandle()
        assertEquals(link, documents(share, saved).state.value.pendingLink)

        // The destination still carries the share after process death; it's neither lost nor taken in again.
        val restored = saved.afterProcessDeath()
        val viewModel = documents(share, restored)
        assertEquals(link, viewModel.state.value.pendingLink)
        viewModel.cancelAdding()

        assertNull(documents(share, restored.afterProcessDeath()).state.value.pendingLink)
        assertTrue(repository.uploads(7).first().isEmpty())
    }

    @Test
    fun `a shared file is asked about once, across process death`() = runTest {
        profile.refresh()
        repository.fileNames[manual] = "Bosch_SHX3AR75UC-manual.pdf"
        val share = SharedDocument.File(manual)
        val saved = SavedStateHandle()
        assertEquals(PickedFile(manual, "Bosch SHX3AR75UC manual"), documents(share, saved).state.value.picked)

        val restored = saved.afterProcessDeath()
        val viewModel = documents(share, restored)
        assertEquals(PickedFile(manual, "Bosch SHX3AR75UC manual"), viewModel.state.value.picked)
        viewModel.addPickedFile("Bosch manual")

        assertNull(documents(share, restored.afterProcessDeath()).state.value.picked)
        assertEquals(listOf("Bosch manual"), repository.uploads(7).first().map { it.displayName })
    }

    @Test
    fun `a picked file survives process death, even before its name was looked up`() = runTest {
        profile.refresh()
        repository.fileNames[manual] = "Bosch_SHX3AR75UC-manual.pdf"
        val saved = SavedStateHandle()
        val beforeDeath = documents(saved = saved)
        // Killed while the name was being looked up.
        beforeDeath.viewModelScope.cancel()
        beforeDeath.onFilePicked(manual)
        assertNull(beforeDeath.state.value.picked)

        val restored = documents(saved = saved.afterProcessDeath())

        assertEquals(PickedFile(manual, "Bosch SHX3AR75UC manual"), restored.state.value.picked)
    }

    @Test
    fun `deleting a document removes it`() = runTest {
        profile.refresh()
        val viewModel = documents()
        viewModel.refresh()

        viewModel.delete(viewModel.state.value.documents.first())

        assertEquals(listOf("GE washer"), viewModel.state.value.documents.map { it.name })
    }

    @Test
    fun `a delete that fails says why`() = runTest {
        profile.refresh()
        val viewModel = documents()
        viewModel.refresh()
        val bosch = viewModel.state.value.documents.first()

        repository.failure = IOException("No connection")
        viewModel.delete(bosch)
        assertEquals(uiText(R.string.documents_delete_offline), viewModel.state.value.message)

        repository.failure = httpError(500, "Something went wrong.")
        viewModel.delete(bosch)
        assertEquals("Something went wrong.".asUiText(), viewModel.state.value.message)
        assertEquals(2, viewModel.state.value.documents.size)
    }

    @Test
    fun `an upload can be retried or dismissed`() = runTest {
        profile.refresh()
        val viewModel = documents()
        repository.addLink(7, "Warranty", "https://example.com/warranty.pdf")
        val upload = viewModel.state.value.uploads.single()

        viewModel.retry(upload)
        viewModel.dismiss(upload)

        assertEquals(listOf(upload.id), repository.retried)
        assertTrue(viewModel.state.value.uploads.isEmpty())
    }
}
