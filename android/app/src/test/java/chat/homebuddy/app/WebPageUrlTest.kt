package chat.homebuddy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebPageUrlTest {
    @Test
    fun `web pages are opened`() {
        listOf("https://example.com/manual.pdf", "http://example.com/support", "HTTPS://Example.com/").forEach { url ->
            assertEquals(url, webPageUrl(url))
        }
        assertEquals("https://example.com/", webPageUrl("  https://example.com/  "))
    }

    @Test
    fun `anything else never is`() {
        listOf(
            null,
            "",
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "javascript:alert(1)",
            "file:///data/data/chat.homebuddy.app/files/uploads/upload-1.pdf",
            "content://chat.homebuddy.app.provider/secret",
            "tel:911",
            "market://details?id=com.example",
            "https://",
            "https:///no-host",
            "example.com/manual.pdf",
            "https://exa mple.com",
        ).forEach { url -> assertNull(url, webPageUrl(url)) }
    }
}
