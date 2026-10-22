package chat.homebuddy.core.network

import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** An HTTP error before the stream started (401, 403, 409 still answering, 429...). */
class ApiException(val error: ApiError) : IOException(error.message)

/**
 * Streams an answer. Events arrive in order and the flow ends after the server closes the
 * stream. Cancelling the collector cancels the HTTP call; the server still finishes the answer
 * and saves it in the conversation.
 */
class QueryStreamClient(
    private val client: OkHttpClient,
    private val config: ApiConfig,
    private val json: Json = HomeBuddyJson,
) {
    //TODO: Why open/close a new stream per request instead of opening once at the beginning of the session and closing when the app leaves the foreground?
    fun stream(request: QueryRequestDto): Flow<StreamEvent> = callbackFlow {
        val call = client.newCall(
            Request.Builder()
                .url(config.baseUrl + "query/stream")
                .header("Accept", "text/event-stream")
                .post(json.encodeToString(QueryRequestDto.serializer(), request).toRequestBody(JSON))
                .build(),
        )
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    close(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            close(ApiException(parseApiError(response.code, response.body?.string(), response.header("Retry-After"))))
                            return
                        }
                        try {
                            val source = checkNotNull(response.body).source()
                            val parser = SseParser()
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                parser.feed(line)?.let(::parseStreamEvent)?.let { trySendBlocking(it) }
                            }
                            parser.finish()?.let(::parseStreamEvent)?.let { trySendBlocking(it) }
                            close()
                        } catch (e: IOException) {
                            close(e)
                        }
                    }
                }
            },
        )
        awaitClose { call.cancel() }
    }.buffer(capacity = 64)

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
