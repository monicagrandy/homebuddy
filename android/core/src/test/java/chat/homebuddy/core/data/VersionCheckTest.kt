package chat.homebuddy.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCheckTest {
    @Test
    fun `older versions must update`() {
        assertFalse(isVersionSupported("0.9.9", "1.0.0"))
        assertFalse(isVersionSupported("1.2", "1.2.1"))
        assertFalse(isVersionSupported("1.9.0", "1.10.0"))
    }

    @Test
    fun `the same or newer versions are fine`() {
        assertTrue(isVersionSupported("1.0.0", "1.0.0"))
        assertTrue(isVersionSupported("1.10.0", "1.9.9"))
        assertTrue(isVersionSupported("2", "1.9.9"))
        assertTrue(isVersionSupported("1.0.0-dev", "1.0.0"))
    }

    @Test
    fun `unknown or unreadable versions never block`() {
        assertTrue(isVersionSupported("1.0.0", null))
        assertTrue(isVersionSupported("1.0.0", ""))
        assertTrue(isVersionSupported("local-build", "1.0.0"))
        assertTrue(isVersionSupported("1.0.0", "soon"))
    }
}
