package com.mythara.secret.observe.vosk

import android.content.Context
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
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Mythara's local copy of Vosk's Speaker Model — the small
 * (~13MB) neural net that produces a fixed-dimension x-vector
 * embedding for every utterance. M8.4 Speaker ID feeds these vectors
 * into a cosine-match against enrolled speakers so each Observe
 * transcript can carry a `speaker:<name>` facet.
 *
 * On-disk layout:
 *
 *   filesDir/vosk-speaker-model/
 *     ├── final.raw    ← extracted Alpha Cephei spk model files
 *     ├── mean.vec
 *     ├── transform.mat
 *     └── conf/
 *
 *   cacheDir/
 *     ├── vosk-model-spk-0.4.zip       (during download)
 *     └── vosk-model-spk-0.4.zip.size  (Content-Length marker; cached
 *           zip only honoured when its byte length matches.)
 *
 * Same self-healing zip-marker pattern as VoskModelStore: a truncated
 * download won't be mistaken for a valid cache. Mirrors that store's
 * download → marker → extract → cleanup flow, simplified because
 * there's only one speaker model (no per-language fanout).
 */
@Singleton
class SpeakerModelStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long, val total: Long) : State {
            val pct: Int get() = if (total > 0) ((bytes * 100) / total).toInt() else 0
        }
        data object Extracting : State
        data class Ready(val path: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(
        if (isExtracted()) State.Ready(modelDir.absolutePath) else State.Missing,
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val modelDir: File get() = ctx.filesDir.resolve("vosk-speaker-model")
    private val zipTmp: File get() = ctx.cacheDir.resolve("$MODEL_NAME.zip")
    private val zipMarker: File get() = ctx.cacheDir.resolve("$MODEL_NAME.zip.size")

    /** Speaker models extract to a directory with `final.raw` at minimum. */
    fun isExtracted(): Boolean = modelDir.resolve("final.raw").exists()

    fun pathOrNull(): String? = if (isExtracted()) modelDir.absolutePath else null

    suspend fun ensureReady(): State {
        if (isExtracted()) {
            return State.Ready(modelDir.absolutePath).also { _state.value = it }
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                download()
                extract()
                cleanupZip()
                State.Ready(modelDir.absolutePath)
            }.getOrElse { e ->
                Log.e(TAG, "speaker-model fetch failed: ${e.message}", e)
                runCatching { if (zipTmp.exists()) zipTmp.delete() }
                runCatching { if (zipMarker.exists()) zipMarker.delete() }
                runCatching { if (modelDir.exists()) modelDir.deleteRecursively() }
                State.Failed(e.message ?: e.javaClass.simpleName)
            }.also { _state.value = it }
        }
    }

    fun forget() {
        runCatching { if (modelDir.exists()) modelDir.deleteRecursively() }
        runCatching { if (zipTmp.exists()) zipTmp.delete() }
        runCatching { if (zipMarker.exists()) zipMarker.delete() }
        _state.value = State.Missing
    }

    // ---- internals ----------------------------------------------------------

    private fun isCachedZipComplete(): Boolean {
        if (!zipTmp.exists() || !zipMarker.exists()) return false
        val expected = zipMarker.readText().trim().toLongOrNull() ?: return false
        return expected >= MIN_VALID_BYTES && zipTmp.length() == expected
    }

    private fun download() {
        if (isCachedZipComplete()) {
            Log.d(TAG, "cached spk zip complete; skipping download")
            return
        }
        ctx.cacheDir.mkdirs()
        if (zipTmp.exists()) zipTmp.delete()
        if (zipMarker.exists()) zipMarker.delete()

        val req = Request.Builder().url(MODEL_URL).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} fetching spk model")
            val body = resp.body ?: error("empty body fetching spk model")
            val total = body.contentLength().coerceAtLeast(0L)
            _state.value = State.Downloading(0, total)
            body.byteStream().use { input ->
                FileOutputStream(zipTmp).use { out ->
                    val buf = ByteArray(64 * 1024)
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
            val downloaded = zipTmp.length()
            if (total > 0 && downloaded != total) {
                runCatching { zipTmp.delete() }
                error("download truncated: got $downloaded, expected $total")
            }
            if (downloaded < MIN_VALID_BYTES) {
                runCatching { zipTmp.delete() }
                error("download too small ($downloaded bytes)")
            }
            zipMarker.writeText(downloaded.toString())
            Log.d(TAG, "downloaded spk model: $downloaded bytes")
        }
    }

    private fun extract() {
        _state.value = State.Extracting
        if (modelDir.exists()) modelDir.deleteRecursively()
        modelDir.mkdirs()
        ZipInputStream(zipTmp.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name
                // The spk-model zip extracts as `vosk-model-spk-0.4/<file>`.
                // Strip the leading dir so the contents land directly under
                // our target [modelDir].
                val stripped = name.substringAfter('/', missingDelimiterValue = name)
                if (stripped.isBlank()) { zin.closeEntry(); continue }
                val out = modelDir.resolve(stripped)
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { fos -> zin.copyTo(fos) }
                }
                zin.closeEntry()
            }
        }
        Log.d(TAG, "extracted spk model to ${modelDir.absolutePath}")
    }

    private fun cleanupZip() {
        runCatching { if (zipTmp.exists()) zipTmp.delete() }
        runCatching { if (zipMarker.exists()) zipMarker.delete() }
    }

    companion object {
        private const val TAG = "Mythara/SpkModel"
        const val MODEL_NAME = "vosk-model-spk-0.4"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        // The spk zip is ~13MB. Anything under 5MB is almost certainly
        // an HTML error page or a partial; reject.
        private const val MIN_VALID_BYTES = 5L * 1024 * 1024
        private const val PROGRESS_REPORT_MS = 500L
    }
}
