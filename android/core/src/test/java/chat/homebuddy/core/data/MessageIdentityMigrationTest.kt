package chat.homebuddy.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageIdentityMigrationTest {
    @Test
    fun `adding server IDs preserves existing messages and other cached data`() = checkMigration(3)

    @Test
    fun `adding upload attempt IDs preserves pending uploads and their original identity`() = checkMigration(4)

    private fun checkMigration(fromVersion: Int) = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "identity-migration-$fromVersion.db"
        createDatabaseAt(
            context, name, fromVersion,
            "INSERT INTO messages (conversationId, position, role, content, createdAt) VALUES ('thread', 0, 'user', 'Offline question', NULL)",
            "INSERT INTO users VALUES (1, 'user@example.com', 'User')",
            "INSERT INTO uploads (id, householdId, displayName, docType, url, state, jobId, errorCode, createdAt) " +
                "VALUES ('upload-1', 7, 'Bosch manual', 'manual', 'https://example.com/manual.pdf', 'FAILED', 'job-1', 'index_failed', 1)",
        )
        val database = openAsTheAppDoes(context, name)
        try {
            val legacy = database.conversations().observeMessages("thread").first().single()
            assertEquals("Offline question", legacy.content)
            assertNull(legacy.serverId)
            database.openHelper.readableDatabase.query("SELECT email FROM users").use { row ->
                row.moveToFirst()
                assertEquals("user@example.com", row.getString(0))
            }
            database.conversations().replaceMessages("thread", listOf(legacy.copy(serverId = 101)))
            assertEquals(101, database.conversations().observeMessages("thread").first().single().serverId)
            val pending = upload(url = "https://example.com/manual.pdf", state = UploadState.FAILED, jobId = "job-1").copy(errorCode = "index_failed")
            assertEquals(pending, database.uploads().get("upload-1"))
            database.uploads().upsert(pending.copy(attemptId = "retry-1"))
            assertEquals("retry-1", database.uploads().get("upload-1")?.attemptId)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
