package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.UploadEntity
import chat.homebuddy.core.data.UploadState

/** Terminal results retain the path until their idempotent file cleanup has completed. */
internal val UploadEntity.requiresInputCleanup: Boolean
    get() = state == UploadState.COMPLETED || state == UploadState.DUPLICATE ||
        (state == UploadState.FAILED && errorCode != "network" && errorCode !in RETRYABLE_JOB_ERRORS)

internal val UploadEntity.requiresRecovery: Boolean
    get() = state == UploadState.QUEUED || state == UploadState.UPLOADING || state == UploadState.INDEXING ||
        state == UploadState.COMPLETED || (requiresInputCleanup && filePath != null)
