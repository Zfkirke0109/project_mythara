package com.mythara.minimax

import com.mythara.data.SettingsStore
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Reads the user's MiniMax usage / quota counters from the
 * platform's `coding_plan/remains` endpoint.
 *
 * This endpoint lives on a different host from the OpenAI-compatible
 * chat surface — `platform.minimax.io` instead of `api.minimax.io` —
 * so we can't reuse [MiniMaxClient]'s region-based base URL. We hit
 * it directly with a one-shot OkHttp client. Auth is the SAME
 * Bearer token the chat endpoints use ([SettingsStore.snapshot]'s
 * apiKey), so no separate credential is needed.
 *
 * Returns a list of [ModelRemaining] rows, one per model the user
 * has quota visibility into (chat, speech, music, vision, image,
 * coding-plan, etc.). Each row carries:
 *   - the time bounds of the current refill interval (4-hourly for
 *     most plan tiers)
 *   - usage / total within that interval
 *   - usage / total for the rolling weekly bucket
 *   - the seconds remaining until each bucket refills
 */
@ViewModelScoped
class MiniMaxUsageClient @Inject constructor(
    private val settings: SettingsStore,
) {

    /** A single model's quota row from the API. */
    @Serializable
    data class ModelRemaining(
        @kotlinx.serialization.SerialName("model_name")
        val modelName: String,
        @kotlinx.serialization.SerialName("start_time")
        val startTime: Long = 0,
        @kotlinx.serialization.SerialName("end_time")
        val endTime: Long = 0,
        @kotlinx.serialization.SerialName("remains_time")
        val remainsTime: Long = 0,
        @kotlinx.serialization.SerialName("current_interval_total_count")
        val currentIntervalTotal: Long = 0,
        @kotlinx.serialization.SerialName("current_interval_usage_count")
        val currentIntervalUsage: Long = 0,
        @kotlinx.serialization.SerialName("current_weekly_total_count")
        val currentWeeklyTotal: Long = 0,
        @kotlinx.serialization.SerialName("current_weekly_usage_count")
        val currentWeeklyUsage: Long = 0,
        @kotlinx.serialization.SerialName("weekly_start_time")
        val weeklyStartTime: Long = 0,
        @kotlinx.serialization.SerialName("weekly_end_time")
        val weeklyEndTime: Long = 0,
        @kotlinx.serialization.SerialName("weekly_remains_time")
        val weeklyRemainsTime: Long = 0,
    )

    @Serializable
    private data class BaseResp(
        @kotlinx.serialization.SerialName("status_code") val statusCode: Int = 0,
        @kotlinx.serialization.SerialName("status_msg") val statusMsg: String = "",
    )

    @Serializable
    private data class RemainsResponse(
        @kotlinx.serialization.SerialName("model_remains")
        val modelRemains: List<ModelRemaining> = emptyList(),
        @kotlinx.serialization.SerialName("base_resp")
        val baseResp: BaseResp = BaseResp(),
    )

    /** One-off http client — no need for the streaming-tuned read
     *  timeouts MiniMaxClient uses. Fresh instance per call so a
     *  long-lived ViewModel doesn't pin connections. */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** Fetch the current usage breakdown. Returns:
     *   - Result.success(list) on a 2xx with a non-empty payload
     *   - Result.failure(MissingApiKey) if the user hasn't set an
     *     API key yet
     *   - Result.failure(IOException / ApiException) on transport
     *     or HTTP-error states
     */
    suspend fun fetch(): Result<List<ModelRemaining>> = withContext(Dispatchers.IO) {
        val key = settings.snapshot().apiKey
        if (key.isNullOrBlank()) {
            return@withContext Result.failure(MissingApiKey())
        }
        runCatching {
            val req = Request.Builder()
                .url(USAGE_ENDPOINT)
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code} ${resp.message}")
                }
                val body = resp.body?.string().orEmpty()
                val parsed = json.decodeFromString(RemainsResponse.serializer(), body)
                if (parsed.baseResp.statusCode != 0) {
                    error("MiniMax ${parsed.baseResp.statusCode}: ${parsed.baseResp.statusMsg}")
                }
                parsed.modelRemains
            }
        }
    }

    /** Thrown when the user hasn't configured a MiniMax API key
     *  yet. Caller surfaces a "set up API key in Settings" CTA. */
    class MissingApiKey : RuntimeException("MiniMax API key not configured")

    companion object {
        /** Platform endpoint — distinct host from the OpenAI-compat
         *  chat API. Not part of [Region] because the platform
         *  surface itself doesn't have multi-region routing. */
        const val USAGE_ENDPOINT =
            "https://platform.minimax.io/v1/api/openplatform/coding_plan/remains"
    }
}
