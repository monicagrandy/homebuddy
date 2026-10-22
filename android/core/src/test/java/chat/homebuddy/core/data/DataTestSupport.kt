package chat.homebuddy.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import chat.homebuddy.core.network.AccessTokenProvider
import chat.homebuddy.core.network.ApiConfig
import chat.homebuddy.core.network.HomeBuddyApi
import chat.homebuddy.core.network.buildApi
import chat.homebuddy.core.network.buildHttpClient
import java.io.File
import javax.inject.Provider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class FakeScheduler : UploadScheduler {
    val scheduled = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    var cancelledAll = false

    override fun schedule(uploadId: String) {
        scheduled += uploadId
    }

    override fun cancel(uploadId: String) {
        cancelled += uploadId
    }

    override fun cancelAll() {
        cancelledAll = true
    }
}

private object SignedIn : AccessTokenProvider {
    override suspend fun accessToken(forceRefresh: Boolean) = "token"
    override suspend fun onSessionRejected() = Unit
}

fun inMemoryDatabase(): HomeBuddyDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HomeBuddyDatabase::class.java)
        .allowMainThreadQueries()
        .build()

fun apiFor(server: MockWebServer): HomeBuddyApi {
    val config = ApiConfig(baseUrl = server.url("/").toString(), clientHeader = "android/test")
    return buildApi(config, buildHttpClient(config, Provider { SignedIn }))
}

fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

fun job(status: String, jobId: String? = "job-1", entryId: String = "bosch-manual", errorCode: String? = null, error: String? = null, duplicateOf: String? = null) =
    """{"job_id": ${jobId?.let { "\"$it\"" } ?: "null"}, "entry_id": "$entryId", "status": "$status",
        "duplicate_of": ${duplicateOf?.let { "\"$it\"" } ?: "null"}, "error_code": ${errorCode?.let { "\"$it\"" } ?: "null"},
        "error": ${error?.let { "\"$it\"" } ?: "null"}, "chunks_indexed": null, "attempts": 1}"""

fun documentsJson(vararg entries: Pair<String, String>) = entries.joinToString(prefix = "[", postfix = "]") { (entryId, status) ->
    """{"id": 1, "entry_id": "$entryId", "display_name": "$entryId", "download_url": null, "doc_type": "manual", "uploaded_at": null, "status": "$status"}"""
}

fun upload(id: String = "upload-1", file: File? = null, url: String? = null, state: UploadState = UploadState.QUEUED, jobId: String? = null) =
    UploadEntity(
        id = id,
        householdId = 7,
        displayName = "Bosch manual",
        docType = "manual",
        filePath = file?.absolutePath,
        url = url,
        state = state,
        jobId = jobId,
        createdAt = 1L,
    )
