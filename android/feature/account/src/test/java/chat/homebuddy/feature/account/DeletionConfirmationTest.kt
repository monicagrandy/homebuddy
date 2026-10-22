package chat.homebuddy.feature.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionConfirmationTest {
    @Test
    fun `only the exact word confirms`() {
        assertTrue(canConfirmDeletion("DELETE"))
        assertTrue(canConfirmDeletion("  DELETE "))
        assertFalse(canConfirmDeletion("delete"))
        assertFalse(canConfirmDeletion("DELET"))
        assertFalse(canConfirmDeletion(""))
    }
}
