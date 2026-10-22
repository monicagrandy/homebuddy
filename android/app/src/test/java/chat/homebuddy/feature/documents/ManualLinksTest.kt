package chat.homebuddy.feature.documents

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualLinksTest {
    @Test
    fun `https links are accepted with their site and a suggested name`() {
        assertEquals(
            ManualLink.Valid(
                url = "https://www.bosch-home.com/manuals/SHX3AR75UC_owners-manual.pdf",
                host = "bosch-home.com",
                suggestedName = "SHX3AR75UC owners manual",
            ),
            parseManualLink("  https://www.bosch-home.com/manuals/SHX3AR75UC_owners-manual.pdf "),
        )
    }

    @Test
    fun `a link without a path is named after its site`() {
        assertEquals("support.ge.com", (parseManualLink("https://support.ge.com") as ManualLink.Valid).suggestedName)
    }

    @Test
    fun `plain http is refused`() {
        assertEquals(ManualLink.NotSecure, parseManualLink("http://example.com/manual.pdf"))
    }

    @Test
    fun `things that aren't web links are refused`() {
        listOf(
            null,
            "",
            "WIFI:S:HomeNetwork;T:WPA;P:secret;;",
            "tel:+18005551234",
            "javascript:alert(1)",
            "Bosch dishwasher manual",
            "https://exa mple.com",
            "https://",
        ).forEach { assertEquals(it, ManualLink.NotALink, parseManualLink(it)) }
    }

    @Test
    fun `file names become readable document names`() {
        assertEquals("Bosch SHX3AR75UC manual", nameFromFileName("Bosch_SHX3AR75UC-manual.pdf"))
        assertEquals("Warranty 2024", nameFromFileName("warranty 2024.PDF"))
        assertEquals("Lease", nameFromFileName("lease"))
        assertEquals(null, nameFromFileName("___.pdf"))
        assertEquals(null, nameFromFileName(null))
    }
}
