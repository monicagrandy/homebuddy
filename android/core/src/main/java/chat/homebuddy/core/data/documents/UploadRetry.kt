package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.UploadEntity
import chat.homebuddy.core.data.UploadState

enum class UploadRetry { NONE, RESUME, NEW_ATTEMPT }

internal val RETRYABLE_JOB_ERRORS = setOf("index_failed", "download_failed")

/** Shared by the repository and UI; permanent rejections require a different document or URL. */
val UploadEntity.retryAction: UploadRetry
    get() {
        if (state != UploadState.FAILED) return UploadRetry.NONE
        val hasInput = filePath != null || url != null
        return when {
            errorCode == "network" && (jobId != null || hasInput) -> UploadRetry.RESUME
            errorCode in RETRYABLE_JOB_ERRORS && hasInput -> UploadRetry.NEW_ATTEMPT
            else -> UploadRetry.NONE
        }
    }
