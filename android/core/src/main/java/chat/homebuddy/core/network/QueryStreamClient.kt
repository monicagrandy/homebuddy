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
 *
 * Each question is its own request: the backend answers a POST to /query/stream with one SSE
 * response and closes it when the answer is done. Keeping a connection open for the whole session
 * would need a different protocol on the server (WebSockets) for no gain, since nothing is sent
 * between questions, and Android doesn't keep sockets alive in the background anyway. Pushing
 * news between questions, such as "your document is ready", is a job for FCM later.
 */
class QueryStreamClient(
    private val client: OkHttpClient,
    private val config: ApiConfig,
    private val json: Json = HomeBuddyJson,
) {
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
                    try {
                        response.use {
                            if (!response.isSuccessful) {
                                throw ApiException(parseApiError(response.code, response.body.readErrorBody(), response.header("Retry-After"), json))
                            }
                            val body = response.body ?: throw IOException("The server returned an empty response.")
                            val contentType = body.contentType()
                            if (contentType?.type != "text" || contentType.subtype != "event-stream") {
                                throw IOException("The server did not return an event stream.")
                            }
                            val source = body.source()
                            val parser = SseParser()
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                parser.feed(line)?.let(::parseStreamEvent)?.let { trySendBlocking(it) }
                            }
                            parser.finish()?.let(::parseStreamEvent)?.let { trySendBlocking(it) }
                        }
                        close()
                    } catch (failure: Exception) {
                        close(failure as? IOException ?: IOException("Couldn't read the server response.", failure))
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
