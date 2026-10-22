package chat.homebuddy.feature.documents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import chat.homebuddy.core.designsystem.HomeBuddyTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DocumentsScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val everything = DocumentsUiState(
        loaded = true,
        uploads = listOf(
            UploadItem("1", "GE washer manual", UploadStatus.UPLOADING),
            UploadItem("2", "Nest thermostat", UploadStatus.READING),
            UploadItem("3", "Kitchen faucet", UploadStatus.FAILED, message = NOT_A_PDF_LINK, openUrl = "https://example.com/support"),
            UploadItem("4", "Old furnace scan", UploadStatus.FAILED, message = "This PDF has no readable text (it may be a scan)."),
            UploadItem("5", "Bosch dishwasher (copy)", UploadStatus.DUPLICATE, message = "Already in your documents."),
        ),
        documents = listOf(
            DocumentItem("bosch", "Bosch dishwasher manual", "manual", indexing = false),
            DocumentItem("warranty", "Home warranty", "warranty", indexing = true),
        ),
    )

    private fun capture(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        // Progress indicators never settle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HomeBuddyTheme(darkTheme = dark) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun empty() = capture("documents_empty") { DocumentsScreen(DocumentsUiState(loaded = true), DocumentsActions()) }

    @Test
    fun everyState() = capture("documents_every_state") { DocumentsScreen(everything, DocumentsActions()) }

    @Test
    fun everyStateDark() = capture("documents_every_state_dark", dark = true) { DocumentsScreen(everything, DocumentsActions()) }

    @Test
    fun errorBannerLargeFont() = capture("documents_error_font_200", fontScale = 2f) {
        DocumentsScreen(
            everything.copy(message = "Couldn't reach Home Buddy to delete it. Try again when you're online.", uploads = emptyList()),
            DocumentsActions(),
        )
    }
}
