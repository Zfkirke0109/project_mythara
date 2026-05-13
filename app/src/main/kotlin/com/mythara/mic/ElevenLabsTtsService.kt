package com.mythara.mic

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ElevenLabs-backed TTS. An optional alternative to Android's built-in
 * [android.speech.tts.TextToSpeech] for the assistant's spoken replies.
 *
 * Why a separate service rather than swapping the engine in [Tts]:
 *  - ElevenLabs is HTTP-based. Latency and failure modes are different
 *    from the synchronous Android engine — we need to download the
 *    audio file first and play it locally via [MediaPlayer]. Mixing
 *    that path into the synchronous TTS wrapper would muddy state.
 *  - The Android engine carries fallback value: even on a bad-network
 *    day or API error, the user still hears Lumi. [Tts] keeps the
 *    Android engine as the always-available baseline.
 *
 * Flow:
 *   1. POST /v1/text-to-speech/{voice_id} with the text + voice
 *      settings → ElevenLabs streams back MP3 bytes.
 *   2. We buffer the response to filesDir/tts/<ts>.mp3 (small files;
 *      typical Lumi reply is ~5-20 KB at 64kbps).
 *   3. MediaPlayer plays the file. We expose start/completion via
 *      a single suspend `speak()` that resumes when playback ends.
 *
 * Mood-aware prosody: ElevenLabs' `voice_settings.stability` and
 * `style` map roughly to Android TTS's pitch/rate. We adjust them
 * subtly when the user mood trend is anxious/sad/frustrated vs
 * excited/happy, same way [Tts.applyProsody] does for Android.
 */
@Singleton
class ElevenLabsTtsService @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    data class Outcome(val ok: Boolean, val detail: String? = null, val code: String? = null)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }

    @Volatile private var player: MediaPlayer? = null

    /**
     * Synthesise [text] with [voiceId], download the MP3 to private
     * storage, and play it back. Suspends until playback finishes (or
     * fails). [onStart] / [onDone] callbacks fire on the main thread
     * so [Tts] can flip its `speaking` StateFlow.
     */
    suspend fun speak(
        text: String,
        apiKey: String,
        voiceId: String,
        userMoodTrend: String? = null,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
    ): Outcome {
        if (text.isBlank()) return Outcome(false, "empty text", "empty")
        if (apiKey.isBlank()) return Outcome(false, "missing api key", "missing_api_key")

        val mp3File = downloadAudio(text, apiKey, voiceId, userMoodTrend)
            ?: return Outcome(false, "audio download failed", "download_failed")

        return playFile(mp3File, onStart, onDone)
    }

    private suspend fun downloadAudio(
        text: String,
        apiKey: String,
        voiceId: String,
        userMoodTrend: String?,
    ): File? = withContext(Dispatchers.IO) {
        val (stability, style) = prosodyFor(userMoodTrend)
        val body = TtsRequest(
            text = text,
            modelId = DEFAULT_MODEL,
            voiceSettings = VoiceSettings(
                stability = stability,
                similarityBoost = 0.75,
                style = style,
                useSpeakerBoost = true,
            ),
        )
        val bodyJson = runCatching { json.encodeToString(TtsRequest.serializer(), body) }
            .getOrElse {
                Log.w(TAG, "encode failed: ${it.message}")
                return@withContext null
            }
        val req = Request.Builder()
            .url("$BASE_URL/v1/text-to-speech/$voiceId?output_format=$OUTPUT_FORMAT")
            .addHeader("xi-api-key", apiKey)
            .addHeader("accept", "audio/mpeg")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val result = runCatching { http.newCall(req).execute() }.getOrElse {
            Log.w(TAG, "ElevenLabs call threw", it)
            return@withContext null
        }
        result.use { res ->
            if (!res.isSuccessful) {
                val errBody = res.body?.string().orEmpty()
                val parsed = parseElevenLabsError(errBody) ?: errBody.take(160)
                Log.w(TAG, "ElevenLabs synth ${res.code}: $parsed")
                return@withContext null
            }
            val dir = File(ctx.filesDir, TTS_DIR).apply { mkdirs() }
            val file = File(dir, "tts-${System.currentTimeMillis()}.mp3")
            val bytes = res.body?.bytes() ?: run {
                Log.w(TAG, "empty body")
                return@withContext null
            }
            FileOutputStream(file).use { it.write(bytes) }
            // Best-effort housekeeping: keep at most KEEP_RECENT files,
            // delete the older ones so the photos/tts dir doesn't grow
            // unbounded. We don't need historical TTS audio.
            pruneOldFiles(dir)
            file
        }
    }

    private suspend fun playFile(
        file: File,
        onStart: () -> Unit,
        onDone: () -> Unit,
    ): Outcome = withContext(Dispatchers.Main) {
        // Stop any currently-playing utterance before starting the new
        // one. ElevenLabs route is sequential — overlapping plays would
        // create a chorus.
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        val mp = MediaPlayer()
        player = mp
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                runCatching { if (mp.isPlaying) mp.stop() }
                runCatching { mp.release() }
                if (player === mp) player = null
                onDone()
            }
            mp.setOnPreparedListener {
                onStart()
                runCatching { mp.start() }.onFailure {
                    Log.w(TAG, "start failed", it)
                    onDone()
                    if (cont.isActive) cont.resumeWithException(it)
                }
            }
            mp.setOnCompletionListener {
                onDone()
                runCatching { mp.release() }
                if (player === mp) player = null
                if (cont.isActive) cont.resume(Outcome(true))
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                onDone()
                runCatching { mp.release() }
                if (player === mp) player = null
                if (cont.isActive) cont.resume(Outcome(false, "MediaPlayer $what/$extra", "playback"))
                true
            }
            runCatching {
                mp.setDataSource(file.absolutePath)
                mp.prepareAsync()
            }.onFailure {
                Log.w(TAG, "prepare threw", it)
                onDone()
                runCatching { mp.release() }
                if (player === mp) player = null
                if (cont.isActive) cont.resume(Outcome(false, "${it.message}", "prepare"))
            }
        }
    }

    /** Stop whatever is currently playing. Idempotent. */
    fun stop() {
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        player = null
    }

    /**
     * One voice from the user's ElevenLabs voice library. Exposed to
     * the UI so Settings can render a dropdown picker keyed on
     * [name] but mapped to [voiceId] internally.
     */
    data class Voice(
        val voiceId: String,
        val name: String,
        val category: String?,
        val description: String? = null,
    )

    data class VoicesOutcome(
        val ok: Boolean,
        val voices: List<Voice> = emptyList(),
        val detail: String? = null,
        val code: String? = null,
    )

    /**
     * GET /v1/voices — list every voice this key can use (premade +
     * cloned + shared favorites). Used by Settings to build the voice
     * dropdown so the user picks a name, not an opaque 20-char id.
     */
    suspend fun fetchVoices(apiKey: String): VoicesOutcome = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext VoicesOutcome(false, detail = "empty key", code = "empty")
        val req = Request.Builder()
            .url("$BASE_URL/v1/voices")
            .addHeader("xi-api-key", apiKey)
            .get()
            .build()
        val result = runCatching { http.newCall(req).execute() }.getOrElse {
            return@withContext VoicesOutcome(false, detail = it.message ?: "network failure", code = "network")
        }
        result.use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val parsed = parseElevenLabsError(raw) ?: "HTTP ${res.code}"
                Log.w(TAG, "fetchVoices ${res.code}: $raw")
                return@withContext VoicesOutcome(false, detail = parsed, code = "http_${res.code}")
            }
            val parsed = runCatching {
                json.decodeFromString(VoicesEnvelope.serializer(), raw)
            }.getOrNull()
                ?: return@withContext VoicesOutcome(false, detail = "couldn't parse voices response", code = "parse")
            val voices = parsed.voices
                .map { v ->
                    Voice(
                        voiceId = v.voice_id,
                        name = v.name.ifBlank { v.voice_id.take(8) + "…" },
                        category = v.category,
                        description = v.description,
                    )
                }
                // Premade voices first (familiar names like Rachel /
                // Adam), then cloned/custom voices alphabetically.
                .sortedWith(compareBy({ premadeRank(it.category) }, { it.name.lowercase() }))
            VoicesOutcome(true, voices = voices)
        }
    }

    private fun premadeRank(category: String?): Int = when (category) {
        "premade" -> 0
        "professional" -> 1
        "cloned" -> 2
        else -> 3
    }

    @Serializable
    private data class VoicesEnvelope(val voices: List<VoiceDto> = emptyList())

    @Serializable
    private data class VoiceDto(
        @kotlinx.serialization.SerialName("voice_id") val voice_id: String,
        val name: String = "",
        val category: String? = null,
        val description: String? = null,
    )

    /**
     * Cheap one-shot key probe. Uses `/v1/models` rather than the
     * earlier `/v1/user` because most scoped ElevenLabs keys (in
     * particular TTS-only keys generated from the API key UI with
     * limited permissions) lack the `user_read` permission and 401
     * on `/v1/user` even when they're perfectly fine for synthesis.
     * `/v1/models` works for every key tier we care about.
     *
     * Parses the JSON error body when ElevenLabs returns 4xx so the
     * UI can surface "Invalid API key" / "Missing permission X" /
     * "Free tier exceeded" instead of an opaque `HTTP 401`.
     */
    suspend fun validate(apiKey: String): Outcome = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Outcome(false, "empty key", "empty")
        val req = Request.Builder()
            .url("$BASE_URL/v1/models")
            .addHeader("xi-api-key", apiKey)
            .get()
            .build()
        val result = runCatching { http.newCall(req).execute() }.getOrElse {
            return@withContext Outcome(false, it.message ?: "network failure", "network")
        }
        result.use { res ->
            if (res.isSuccessful) {
                return@withContext Outcome(true, "key OK · models endpoint reachable")
            }
            val raw = res.body?.string().orEmpty()
            val parsedMsg = parseElevenLabsError(raw)
            val detail = parsedMsg ?: when (res.code) {
                401 -> "API key rejected — copy a fresh key from elevenlabs.io/app/settings/api-keys"
                403 -> "API key valid but missing permissions for this endpoint"
                429 -> "Free-tier rate limit hit — wait a minute and re-validate"
                else -> "HTTP ${res.code}"
            }
            Log.w(TAG, "validate ${res.code}: $raw")
            Outcome(false, detail, "http_${res.code}")
        }
    }

    /**
     * ElevenLabs returns errors as one of:
     *   {"detail":"Invalid API key"}
     *   {"detail":{"status":"missing_permissions","message":"..."}}
     *   {"detail":[{"loc":[...], "msg":"...", "type":"..."}]} (FastAPI 422)
     *
     * We pull a human-readable string out of whichever shape we get,
     * returning null when we can't make sense of the body (caller
     * falls back to a generic HTTP-code message).
     */
    private fun parseElevenLabsError(raw: String): String? {
        if (raw.isBlank()) return null
        return runCatching {
            val element = json.parseToJsonElement(raw)
            val detail = (element as? kotlinx.serialization.json.JsonObject)?.get("detail")
                ?: return@runCatching null
            when {
                detail is kotlinx.serialization.json.JsonPrimitive -> detail.content
                detail is kotlinx.serialization.json.JsonObject -> {
                    val msg = (detail["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val status = (detail["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    when {
                        msg != null && status != null -> "$status — $msg"
                        msg != null -> msg
                        status != null -> status
                        else -> detail.toString().take(160)
                    }
                }
                detail is kotlinx.serialization.json.JsonArray -> {
                    detail.firstOrNull()
                        ?.let { it as? kotlinx.serialization.json.JsonObject }
                        ?.get("msg")
                        ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                        ?.content
                }
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Map a mood trend to (stability, style) on ElevenLabs' 0..1
     * scales. Higher stability = less variable / calmer; lower style
     * = more conversational. Subtle tweaks only — we want the voice
     * to feel responsive, not theatrical.
     */
    private fun prosodyFor(userMoodTrend: String?): Pair<Double, Double> {
        return when (userMoodTrend) {
            // Calmer, warmer — push stability up + style down for an
            // anxious/sad user. Voice sounds more measured.
            "anxious", "sad", "frustrated" -> 0.70 to 0.20
            // Slightly more expressive when the user is upbeat.
            "excited", "happy" -> 0.40 to 0.50
            // Neutral / unknown — ElevenLabs' typical defaults.
            else -> 0.50 to 0.35
        }
    }

    private fun pruneOldFiles(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP_RECENT).forEach { runCatching { it.delete() } }
    }

    // ---------- wire format DTOs ----------

    @Serializable
    private data class TtsRequest(
        val text: String,
        @kotlinx.serialization.SerialName("model_id") val modelId: String,
        @kotlinx.serialization.SerialName("voice_settings") val voiceSettings: VoiceSettings? = null,
    )

    @Serializable
    private data class VoiceSettings(
        val stability: Double,
        @kotlinx.serialization.SerialName("similarity_boost") val similarityBoost: Double,
        val style: Double,
        @kotlinx.serialization.SerialName("use_speaker_boost") val useSpeakerBoost: Boolean,
    )

    companion object {
        private const val TAG = "Mythara/EL-TTS"
        private const val BASE_URL = "https://api.elevenlabs.io"
        /**
         * eleven_turbo_v2_5 — low-latency, multi-lingual model. Best
         * fit for a chat assistant where replies are short and need
         * to start playing in under a second.
         */
        private const val DEFAULT_MODEL = "eleven_turbo_v2_5"
        /**
         * 44.1kHz / 128kbps MP3 — good quality, ~12KB/sec. Stays small
         * enough that our internal-storage cache doesn't bloat.
         */
        private const val OUTPUT_FORMAT = "mp3_44100_128"
        private const val TTS_DIR = "tts"
        private const val KEEP_RECENT = 5
    }
}
