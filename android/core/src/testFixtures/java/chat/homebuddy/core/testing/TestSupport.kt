package chat.homebuddy.core.testing

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** A failed response from the backend, as Retrofit throws it. */
fun httpError(code: Int, detail: String = "Something went wrong."): HttpException =
    HttpException(Response.error<Unit>(code, """{"detail": "$detail"}""".toResponseBody("application/json".toMediaType())))

/** For the parts of a test double that no test needs yet. */
internal fun notFaked(): Nothing = error("Not faked yet. Add it to the test double when a test needs it.")
