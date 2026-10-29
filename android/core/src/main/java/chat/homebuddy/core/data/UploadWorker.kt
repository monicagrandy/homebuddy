package chat.homebuddy.core.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.await
import chat.homebuddy.core.data.documents.UploadOutcome
import chat.homebuddy.core.data.documents.UploadProcessor
import chat.homebuddy.core.data.documents.UploadScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UploadWorkerEntryPoint {
    fun uploadProcessor(): UploadProcessor
}

/** Runs an upload in the background, so switching apps or losing signal doesn't lose it. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uploadId = inputData.getString(KEY_UPLOAD_ID) ?: return Result.failure()
        val processor = EntryPointAccessors.fromApplication(applicationContext, UploadWorkerEntryPoint::class.java).uploadProcessor()
        if (runAttemptCount >= MAX_ATTEMPTS) {
            processor.giveUp(uploadId)
            return Result.failure()
        }
        return when (processor.process(uploadId)) {
            UploadOutcome.FINISHED -> Result.success()
            UploadOutcome.RETRY -> Result.retry()
        }
    }

    companion object {
        const val KEY_UPLOAD_ID = "upload_id"
        const val TAG = "document-upload"
        const val MAX_ATTEMPTS = 10
    }
}

class WorkManagerUploadScheduler(private val context: Context) : UploadScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    override fun schedule(uploadId: String) {
        workManager.enqueueUniqueWork(workName(uploadId), ExistingWorkPolicy.REPLACE, request(uploadId))
    }

    override suspend fun ensureScheduled(uploadId: String) {
        try {
            workManager.enqueueUniqueWork(workName(uploadId), ExistingWorkPolicy.KEEP, request(uploadId)).await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw IOException("Couldn't restore background uploads.", failure)
        }
    }

    private fun request(uploadId: String) =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_UPLOAD_ID to uploadId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(UploadWorker.TAG)
            .build()

    override fun cancel(uploadId: String) {
        workManager.cancelUniqueWork(workName(uploadId))
    }

    override suspend fun cancelAll() {
        workManager.cancelAllWorkByTag(UploadWorker.TAG).await()
    }

    private fun workName(uploadId: String) = "upload-$uploadId"
}
