package dev.wristbound.companion.hermes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A request the Hermes dashboard answered with an HTTP error. [code] is its machine-readable reason, if any. */
class HermesHttpException(val status: Int, val code: String?, message: String) : IOException(message) {
    val isAuth: Boolean get() = status == 401 || status == 403
}

/** Shared HTTP plumbing for the dashboard client and its sign-in flows. */
object HermesHttp {
    val JSON_MEDIA = "application/json".toMediaType()
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun body(response: Response): String {
        if (!response.isSuccessful) throw error(response)
        return response.body?.string().orEmpty()
    }

    /**
     * FastAPI answers `{"detail": "..."}` or `{"detail": {...}}`; dashboard-auth routes answer
     * `{"error": "session_expired", "detail": "..."}`. Keep whichever reason is present.
     */
    fun error(response: Response): HermesHttpException {
        val raw = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        val detail = obj?.get("detail")
        val text = (detail as? JsonPrimitive)?.contentOrNull
            ?: ((detail as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        val code = (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: ((detail as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
        return HermesHttpException(response.code, code, text ?: "HTTP ${response.code}")
    }
}
