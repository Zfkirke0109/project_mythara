package com.mythara.secret.observe.extract.gemma

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazy fetcher for the Gemma 4 E2B `.litertlm` bundle that powers
 * M8.2.2's on-device fact extractor. Same self-healing pattern as
 * [com.mythara.secret.observe.vosk.VoskModelStore]: download to a
 * known path, sidecar marker records the verified Content-Length,
 * partial / truncated files don't fool subsequent retries.
 *
 * On-disk path:
 *   filesDir/gemma/gemma-4-E2B-it.litertlm        (~2.6GB)
 *   filesDir/gemma/gemma-4-E2B-it.litertlm.size   (Content-Length marker)
 *
 * Gemma 4 E2B is Apache 2.0 — anonymous downloads succeed, no HF token
 * required. The store still injects [HuggingFaceTokenStore] and forwards
 * a Bearer header when a token is saved (harmless for Apache-2.0 endpoints,
 * useful if we ever point [MODEL_URL] at a gated mirror).
 *
 * The download URL points at the litert-community mirror on Hugging
 * Face, which hosts the `.litertlm` bundle for the LiteRT-LM runtime.
 * If Hugging Face ever moves the file, swap [MODEL_URL] for whatever
 * mirror is currently authoritative — no other code change needed.
 */
@Singleton
class GemmaModelStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val hfTokenStore: HuggingFaceTokenStore,
) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long, val total: Long) : State {
            val pct: Int get() = if (total > 0) ((bytes * 100) / total).toInt() else 0
        }
        data class Ready(val path: String) : State
        data class Failed(val message: String) : State
    }

    val modelDir: File get() = ctx.filesDir.resolve("gemma").apply { mkdirs() }
    val modelFile: File get() = modelDir.resolve(MODEL_NAME)
    private val sizeMarker: File get() = modelDir.resolve("$MODEL_NAME.size")

    private val _state = MutableStateFlow<State>(
        if (isAvailable()) State.Ready(modelFile.absolutePath) else State.Missing,
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Generous read timeout — Hugging Face occasionally pauses mid-stream
        // on big transfers. A 5-minute window is comfortable for a 530MB file
        // even on slower connections.
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    fun isAvailable(): Boolean {
        if (!modelFile.exists()) return false
        if (modelFile.length() < MIN_VALID_BYTES) return false
        if (!sizeMarker.exists()) return modelFile.length() >= MIN_VALID_BYTES
        val expected = sizeMarker.readText().trim().toLongOrNull() ?: return false
        return expected == modelFile.length()
    }

    fun pathOrNull(): String? = if (isAvailable()) modelFile.absolutePath else null

    suspend fun ensureReady(): State {
        if (isAvailable()) {
            _state.value = State.Ready(modelFile.absolutePath)
            return _state.value
        }
        return withContext(Dispatchers.IO) {
            val attempt = runCatching {
                download()
                State.Ready(modelFile.absolutePath)
            }
            attempt.getOrElse { e ->
                Log.e(TAG, "Gemma fetch failed: ${e.message}", e)
                runCatching { if (modelFile.exists()) modelFile.delete() }
                runCatching { if (sizeMarker.exists()) sizeMarker.delete() }
                val msg = e.message ?: e.javaClass.simpleName
                // Gemma 4 E2B is Apache 2.0 so 401 is unlikely here, but keep
                // the friendly hint anyway in case we ever repoint at a gated
                // mirror or the user is rate-limited.
                val friendlier = if (msg.contains("401") || msg.contains("403")) {
                    "$msg — try the manual `.litertlm` import instead."
                } else msg
                State.Failed(friendlier)
            }.also { _state.value = it }
        }
    }

    fun forgetModel() {
        runCatching {
            if (modelFile.exists()) modelFile.delete()
            if (sizeMarker.exists()) sizeMarker.delete()
        }
        _state.value = State.Missing
    }

    /**
     * Import a `.litertlm` file the user has already downloaded (typically
     * from Hugging Face or Kaggle). Useful for offline installs, or if the
     * user prefers to grab a fresh bundle via their browser rather than
     * waiting on the in-app stream of a 2.6GB file.
     *
     * Reuses the same on-disk layout + size-marker contract as the
     * direct-download path, so subsequent `isAvailable()` checks pass
     * and `GemmaExtractor` can load it normally.
     */
    suspend fun importFromUri(uri: Uri): State = withContext(Dispatchers.IO) {
        runCatching {
            if (modelFile.exists()) modelFile.delete()
            if (sizeMarker.exists()) sizeMarker.delete()
            modelDir.mkdirs()

            // ContentResolver may or may not provide a Content-Length up
            // front depending on the document provider — Drive/Downloads
            // usually do, generic providers may not. We render -1 for the
            // total in that case; the UI still gets the byte count.
            val approxSize = runCatching {
                ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            _state.value = State.Downloading(0, approxSize)

            val input = ctx.contentResolver.openInputStream(uri)
                ?: error("Could not open the selected file")
            input.use { src ->
                FileOutputStream(modelFile).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var read = 0L
                    var lastReportMs = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs > PROGRESS_REPORT_MS) {
                            lastReportMs = now
                            _state.update { State.Downloading(read, approxSize) }
                        }
                    }
                }
            }

            val size = modelFile.length()
            if (size < MIN_VALID_BYTES) {
                modelFile.delete()
                error("File too small ($size bytes) — does not look like a valid .litertlm bundle")
            }
            sizeMarker.writeText(size.toString())
            Log.d(TAG, "imported Gemma .litertlm ($size bytes) → ${modelFile.absolutePath}")
            State.Ready(modelFile.absolutePath).also { _state.value = it }
        }.getOrElse { e ->
            Log.e(TAG, "import failed: ${e.message}", e)
            runCatching { if (modelFile.exists()) modelFile.delete() }
            runCatching { if (sizeMarker.exists()) sizeMarker.delete() }
            State.Failed(e.message ?: e.javaClass.simpleName).also { _state.value = it }
        }
    }

    private suspend fun download() {
        if (modelFile.exists()) modelFile.delete()
        if (sizeMarker.exists()) sizeMarker.delete()

        // Pick up the user's HF token at request time so a freshly-saved
        // token is honoured without restarting the app. Token is never
        // logged; OkHttp's logger redacts the Authorization header per
        // GitHubClient's pattern, but we use a fresh client here without
        // logging anyway.
        val token = hfTokenStore.token()
        val reqBuilder = Request.Builder()
            .url(MODEL_URL)
            .header("User-Agent", "Mythara/0.0.1 (Android)")
        if (!token.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $token")
        }
        val req = reqBuilder.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} fetching Gemma model")
            val body = resp.body ?: error("empty body fetching Gemma model")
            val total = body.contentLength().coerceAtLeast(0L)
            _state.value = State.Downloading(0, total)
            body.byteStream().use { input ->
                FileOutputStream(modelFile).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var read = 0L
                    var lastReportMs = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs > PROGRESS_REPORT_MS) {
                            lastReportMs = now
                            _state.update { State.Downloading(read, total) }
                        }
                    }
                }
            }
            val downloaded = modelFile.length()
            if (total > 0 && downloaded != total) {
                modelFile.delete()
                error("download truncated: $downloaded / $total")
            }
            if (downloaded < MIN_VALID_BYTES) {
                modelFile.delete()
                error("download too small ($downloaded bytes) — possibly an HTML error page")
            }
            sizeMarker.writeText(downloaded.toString())
            Log.d(TAG, "Gemma ready: $downloaded bytes at ${modelFile.absolutePath}")
        }
    }

    companion object {
        private const val TAG = "Mythara/Gemma"
        // Gemma 4 E2B (Edge 2B effective parameters) — Apache 2.0, the
        // mobile-first variant of Gemma 4. Bundle ships in the new
        // `.litertlm` format consumed by the LiteRT-LM runtime.
        const val MODEL_NAME = "gemma-4-E2B-it.litertlm"
        const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
        // Canonical size is ~2.59GB. Require at least 2GB to clear the
        // "did we just download an HTML error page?" floor while still
        // allowing for upstream re-quantisations that shave a few hundred MB.
        private const val MIN_VALID_BYTES = 2_000L * 1024 * 1024
        private const val PROGRESS_REPORT_MS = 500L

        /** Identifier embedded in extracted-record provenance / facets. */
        const val MODEL_ID = "gemma-4-e2b-it"
    }
}
