package com.mythara.secret.observe

import android.content.Context
import android.util.Log
import com.mythara.growth.LearningJournal
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.extract.LearningExtractor
import com.mythara.secret.observe.vault.LearningVault
import com.mythara.secret.observe.vosk.VoskAsr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Observe pipeline at run time.
 *
 *   AudioRecorder (16 kHz mono PCM)
 *     ↓ ShortArray frames
 *   Vosk Recognizer.acceptWaveForm(...)
 *     ↓ "is this the end of an utterance?"
 *   YES → final transcript json → write to disk under
 *         filesDir/observe/transcripts/<isoTs>.txt
 *         + append a metadata-only journal entry (no transcript text
 *         in the journal — that's the M8.2 extractor's job)
 *   NO  → partial transcript (ignored today; reserved for live-UI in M8.2)
 *
 * Privacy invariants enforced here:
 *  - Audio never leaves the device. We don't even hold raw PCM in
 *    memory past the recogniser's internal buffers; nothing is
 *    persisted to disk on the audio path.
 *  - Transcripts live in `filesDir/observe/transcripts/` and are
 *    auto-purged by [RawDataPurger] after 24h.
 *  - The journal entry only logs lifecycle ("transcript captured,
 *    N words") — never the transcript content. M8.2's Gemma
 *    extractor will lift durable learnings out of these transcripts
 *    before they purge, and only those condensed learnings make it
 *    into the synced repo.
 */
@Singleton
class ObserveSession @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val ctx: Context,
    private val asr: VoskAsr,
    private val embedder: LocalEmbedder,
    private val vault: LearningVault,
    private val extractor: LearningExtractor,
    private val journal: LearningJournal,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var paused: Boolean = false
    @Volatile private var transcriptCount: Int = 0

    val isRunning: Boolean get() = job?.isActive == true
    fun transcriptCountSnapshot(): Int = transcriptCount

    fun start(): Result<Unit> {
        if (isRunning) return Result.success(Unit)
        if (!asr.isReady()) return Result.failure(IllegalStateException("Vosk model not ready"))

        val recorder = AudioRecorder()
        if (!recorder.start()) {
            return Result.failure(IllegalStateException("AudioRecord init failed"))
        }
        val recognizer = runCatching { asr.newRecognizer() }.getOrElse {
            recorder.release()
            return Result.failure(it)
        }

        val transcriptsDir = ctx.filesDir.resolve("observe/transcripts").apply { mkdirs() }
        val buf = ShortArray(recorder.readFrameSamples)

        job = scope.launch {
            try {
                while (isActive) {
                    if (paused) {
                        // Cheap idle while paused; mic released only on stop().
                        kotlinx.coroutines.delay(200)
                        continue
                    }
                    val n = recorder.read(buf)
                    if (n <= 0) continue
                    val isFinal = recognizer.acceptWaveForm(buf, n)
                    if (isFinal) {
                        val text = asr.parseText(recognizer.result)
                        if (text.isNotBlank()) {
                            writeTranscript(transcriptsDir, text)
                            transcriptCount += 1
                        }
                    }
                }
                // Drain final result on graceful stop.
                val tail = asr.parseText(recognizer.finalResult)
                if (tail.isNotBlank()) {
                    writeTranscript(transcriptsDir, tail)
                    transcriptCount += 1
                }
            } catch (t: Throwable) {
                Log.e(TAG, "session loop crashed: ${t.message}", t)
            } finally {
                runCatching { recognizer.close() }
                recorder.stop()
                recorder.release()
                Log.d(TAG, "session ended; transcripts=$transcriptCount")
            }
        }
        Log.d(TAG, "session started")
        return Result.success(Unit)
    }

    fun pause() { paused = true }
    fun resume() { paused = false }

    fun stop() {
        paused = false
        job?.cancel()
        job = null
    }

    private suspend fun writeTranscript(dir: File, text: String) {
        val now = System.currentTimeMillis()
        val base = ISO_FMT.format(Date(now))
        val txtFile = File(dir, "$base.txt")
        runCatching { txtFile.writeText(text, Charsets.UTF_8) }

        // Embedding sidecar: 100-dim float32 vector (little-endian, ~400B).
        // Best-effort — if the embedder isn't ready yet, the transcript
        // is still captured. M8.3 SelfOrganizer will back-fill missing
        // embeddings on its nightly pass.
        var transcriptEmbedding: FloatArray? = null
        var embModelId: String? = null
        if (embedder.isReady()) {
            runCatching {
                val vec = embedder.embed(text)
                val vecFile = File(dir, "$base.vec")
                vecFile.writeBytes(LocalEmbedder.encode(vec))
                transcriptEmbedding = vec
                embModelId = EmbeddingsModelStore.MODEL_ID
            }.onFailure { e ->
                android.util.Log.w(TAG, "embed failed: ${e.message}")
            }
        }

        // ---- Vault writes ----
        // 1. Working-tier record holding the raw transcript text + its
        //    embedding. Stays local; never synced (see MemorySync filter).
        val refId = "transcript:$base"
        vault.add(
            content = text,
            tier = Tier.Working,
            src = "observe:vosk",
            facets = listOf("kind:transcript"),
            embedding = transcriptEmbedding,
            embModel = embModelId,
            ref = refId,
            conf = 0.9,
            now = now,
        )

        // 2. Heuristic-extracted semantic facts. These DO sync — they're
        //    durable observations about the user, not raw audio content.
        //    Quality is coarse today; M8.2.1 replaces this with Gemma.
        var semanticCount = 0
        for (fact in extractor.extract(text)) {
            val factEmbedding = if (embedder.isReady()) {
                runCatching { embedder.embed(fact.content) }.getOrNull()
            } else null
            val added = vault.add(
                content = fact.content,
                tier = Tier.Semantic,
                src = "extract:heuristic",
                facets = fact.facets,
                embedding = factEmbedding,
                embModel = if (factEmbedding != null) EmbeddingsModelStore.MODEL_ID else null,
                ref = refId,
                conf = fact.conf,
                now = now,
            )
            if (added) semanticCount++
        }

        // Metadata-only journal entry — never the transcript text.
        val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val embedNote = transcriptEmbedding?.let { "${it.size}-dim emb" } ?: "no emb"
        journal.append(
            LearningJournal.Entry(
                tsMillis = now,
                kind = "observe",
                note = "captured transcript ($wordCount words, $embedNote, $semanticCount semantic facts)",
            ),
        )
    }

    companion object {
        private const val TAG = "Mythara/Observe"
        private val ISO_FMT = SimpleDateFormat("yyyyMMdd'T'HHmmss'_'SSS'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
}
