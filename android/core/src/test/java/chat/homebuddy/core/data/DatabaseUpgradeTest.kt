package chat.homebuddy.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Upgrades from every schema version a phone may have, the way the app opens its database. A
 * schema change without a migration fails here, rather than deleting uploads on people's phones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseUpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Columns every version since 3 has, so the same rows go into each one.
    private val pendingUploads = arrayOf(
        "INSERT INTO uploads (id, householdId, displayName, docType, filePath, url, state, jobId, entryId, errorCode, errorMessage, createdAt) " +
            "VALUES ('queued', 7, 'Bosch manual', 'manual', '/files/uploads/queued.pdf', NULL, 'QUEUED', NULL, NULL, NULL, NULL, 1)",
        "INSERT INTO uploads (id, householdId, displayName, docType, filePath, url, state, jobId, entryId, errorCode, errorMessage, createdAt) " +
            "VALUES ('indexing', 7, 'GE washer', 'manual', '/files/uploads/indexing.pdf', NULL, 'INDEXING', 'job-2', 'ge-washer', NULL, NULL, 2)",
        "INSERT INTO uploads (id, householdId, displayName, docType, filePath, url, state, jobId, entryId, errorCode, errorMessage, createdAt) " +
            "VALUES ('offline', 7, 'Warranty', 'warranty', NULL, 'https://example.com/warranty.pdf', 'FAILED', NULL, NULL, 'network', 'Check your connection.', 3)",
    )

    private val expectedUploads = listOf(
        UploadEntity("queued", 7, "Bosch manual", "manual", "/files/uploads/queued.pdf", null, UploadState.QUEUED, createdAt = 1),
        UploadEntity("indexing", 7, "GE washer", "manual", "/files/uploads/indexing.pdf", null, UploadState.INDEXING, jobId = "job-2", entryId = "ge-washer", createdAt = 2),
        UploadEntity(
            "offline", 7, "Warranty", "warranty", null, "https://example.com/warranty.pdf", UploadState.FAILED,
            errorCode = "network", errorMessage = "Check your connection.", createdAt = 3,
        ),
    )

    @Test
    fun `upgrading from every version since 3 keeps the uploads waiting to be sent`() = runTest {
        val versions = exportedSchemaVersions().filter { it >= 3 }
        versions.forEach { version ->
            val name = "upgrade-from-$version.db"
            createDatabaseAt(context, name, version, *pendingUploads)
            val database = openAsTheAppDoes(context, name)
            try {
                assertEquals("from version $version", expectedUploads, database.uploads().all().sortedBy { it.createdAt })
                // The current version's schema is exported too, so the next change is tested from it.
                assertEquals(versions.last(), database.openHelper.readableDatabase.version)
            } finally {
                database.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun `upgrading keeps cached messages, with no answer details until their thread is reloaded`() = runTest {
        exportedSchemaVersions().filter { it >= 3 }.forEach { version ->
            val name = "messages-from-$version.db"
            createDatabaseAt(
                context, name, version,
                "INSERT INTO messages (conversationId, position, role, content, createdAt) VALUES ('thread', 0, 'assistant', 'Leave now.', NULL)",
            )
            val database = openAsTheAppDoes(context, name)
            try {
                assertEquals(
                    "from version $version",
                    listOf(MessageEntity("thread", 0, "assistant", "Leave now.", null)),
                    database.conversations().observeMessages("thread").first(),
                )
            } finally {
                database.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun `versions that never shipped start over`() = runTest {
        listOf(1, 2).forEach { version ->
            val name = "upgrade-from-$version.db"
            createDatabaseAt(context, name, version)
            val database = openAsTheAppDoes(context, name)
            try {
                assertTrue(database.uploads().all().isEmpty())
            } finally {
                database.close()
                context.deleteDatabase(name)
            }
        }
    }
}
