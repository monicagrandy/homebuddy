package chat.homebuddy.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkManagerUploadSchedulerTest {
    private lateinit var manager: WorkManager
    private lateinit var scheduler: WorkManagerUploadScheduler

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        manager = WorkManager.getInstance(context)
        scheduler = WorkManagerUploadScheduler(context)
    }

    @After fun tearDown() = WorkManagerTestInitHelper.closeWorkDatabase()

    private fun unfinished() = manager.getWorkInfosForUniqueWork("upload-upload-1").get().filterNot { it.state.isFinished }

    @Test fun `repeated recovery preserves the existing worker identity`() = runTest {
        scheduler.ensureScheduled("upload-1")
        val first = unfinished().single()
        scheduler.ensureScheduled("upload-1")
        assertEquals(first.id, unfinished().single().id)
        assertEquals(first.runAttemptCount, unfinished().single().runAttemptCount)
    }

    @Test fun `recovery replaces missing or finished work but explicit retry replaces unfinished work`() = runTest {
        scheduler.ensureScheduled("upload-1")
        val first = unfinished().single().id
        manager.cancelUniqueWork("upload-upload-1").result.get()
        scheduler.ensureScheduled("upload-1")
        val restored = unfinished().single().id
        assertNotEquals(first, restored)
        scheduler.schedule("upload-1")
        scheduler.ensureScheduled("upload-1")
        assertNotEquals(restored, unfinished().single().id)
    }
}
