package com.mythara.secret.observe.extract.gemma

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.mythara.secret.observe.extract.LearningExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT-LM-backed extractor. Replaces the M8.2.1 MediaPipe Tasks-GenAI
 * path with Google's current on-device runtime (LiteRT-LM 0.11+), which
 * consumes the new `.litertlm` bundle format and auto-dispatches to
 * GPU on Pixel Tensor G3/G4 and NPU on Snapdragon 8 Elite. CPU is the
 * fallback elsewhere.
 *
 * Output shape stays as [LearningExtractor.Extracted] so callers (and
 * the heuristic fallback) compose identically downstream.
 *
 * The model (Gemma 4 E2B, ~2.6GB) is loaded lazily — first [extract]
 * call after the bundle lands on disk pays the ~5–10s init cost. The
 * resident engine is kept across calls; [release] disposes it on
 * shutdown / forget-everything.
 *
 * Prompt design:
 *   - Strict "return ONLY a JSON array" instruction; no markdown, no
 *     prose. Gemma 4 ignores this <5% of the time even with greedy
 *     decoding; the parser tolerates a leading fence / trailing newline
 *     by extracting the first balanced `[...]`.
 *   - Conservative ask: only durable facts (preferences, identity,
 *     attributes, recurring events) that the user would care to
 *     remember next month. The model is told to return [] when in
 *     doubt — much better than over-extraction.
 *   - Topic facets use slug form ("favourite-colour") so they group
 *     cleanly into `semantic/<topic>.jsonl` files in the memory repo.
 *   - The chat template is bundled inside the `.litertlm` file and
 *     applied automatically by [com.google.ai.edge.litertlm.Conversation]
 *     — we pass raw text, not `<start_of_turn>…<end_of_turn>` tokens.
 */
@Singleton
class GemmaExtractor @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val store: GemmaModelStore,
) {

    @Volatile private var engine: Engine? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun isReady(): Boolean = store.isAvailable()

    suspend fun extract(transcript: String): List<LearningExtractor.Extracted> {
        if (transcript.isBlank()) return emptyList()
        if (!store.isAvailable()) return emptyList()
        return withContext(Dispatchers.Default) {
            runCatching {
                val eng = ensureEngine() ?: return@runCatching emptyList()
                val prompt = buildPrompt(transcript)
                // One-shot: each transcript is independent, so a fresh
                // conversation guarantees no state bleed between extractions.
                val reply: Message = eng.createConversation().use { conv ->
                    conv.sendMessage(Message.of(prompt))
                }
                parseFacts(reply.text())
            }.getOrElse { e ->
                Log.w(TAG, "extract failed: ${e.message}")
                emptyList()
            }
        }
    }

    fun release() {
        runCatching { engine?.close() }
        engine = null
    }

    @Synchronized
    private fun ensureEngine(): Engine? {
        engine?.let { return it }
        val path = store.pathOrNull() ?: return null
        return runCatching {
            Log.d(TAG, "loading Gemma 4 E2B from $path")
            // CPU backend is the universally-supported path. GPU/NPU
            // dispatch is a follow-up optimisation — Backend is an enum
            // in 0.8.0, so swapping is a one-line change once we trust
            // the GPU path on Tensor G3/G4.
            val config = EngineConfig(
                modelPath = path,
                backend = Backend.CPU,
            )
            Engine(config).also { eng ->
                eng.initialize()
                engine = eng
            }
        }.getOrElse { e ->
            Log.e(TAG, "Gemma init failed: ${e.message}", e)
            null
        }
    }

    /**
     * Flatten a [Message] into the concatenated text of its [Content.Text]
     * parts. Gemma 4 only emits text for extraction prompts, but the API
     * supports mixed image/audio content; we ignore non-text parts.
     */
    private fun Message.text(): String =
        contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private fun buildPrompt(transcript: String): String {
        // LiteRT-LM's Conversation applies the model's chat template
        // automatically (chat_template.jinja inside the bundle), so we
        // just send our system + transcript content as a single user
        // turn.
        return buildString {
            append(SYSTEM_PROMPT)
            append("\n\nTranscript:\n```\n")
            append(transcript.take(MAX_TRANSCRIPT_CHARS))
            append("\n```\n\n")
            append("Return the JSON array now.")
        }
    }

    /**
     * Forgiving parser. Looks for the outermost balanced `[...]` in the
     * model's response, parses it as JSON, then walks each object.
     * Anything malformed gets quietly dropped.
     */
    private fun parseFacts(response: String): List<LearningExtractor.Extracted> {
        val arrayText = extractFirstJsonArray(response) ?: return emptyList()
        val arr: JsonArray = runCatching {
            json.parseToJsonElement(arrayText) as? JsonArray
        }.getOrNull() ?: return emptyList()

        val out = mutableListOf<LearningExtractor.Extracted>()
        for (el in arr) {
            val obj = (el as? JsonObject) ?: continue
            val content = obj["content"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
            if (content.isBlank() || content.length > MAX_CONTENT_LEN) continue
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNullSafe()?.trim()?.lowercase().orEmpty()
            val topicRaw = obj["topic"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
            val topic = slug(topicRaw)
            val facets = buildList {
                if (kind.isNotBlank()) add("kind:$kind")
                if (topic.isNotBlank()) add("topic:$topic")
                add("extractor:${GemmaModelStore.MODEL_ID}")
            }
            out.add(
                LearningExtractor.Extracted(
                    content = content,
                    facets = facets,
                    // Gemma 4 E2B extractions are sharper than 3-1B — bump
                    // baseline confidence to 0.85. Downgrade later if
                    // calibration shows false positives.
                    conf = 0.85,
                ),
            )
        }
        return out.distinctBy { it.content.lowercase() }
    }

    private fun extractFirstJsonArray(text: String): String? {
        val start = text.indexOf('[')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '[') depth++
            else if (c == ']') {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun slug(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "misc" }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { this.content }.getOrNull()

    @Serializable
    private data class Fact(val content: String, val kind: String? = null, val topic: String? = null)

    companion object {
        private const val TAG = "Mythara/Gemma"
        private const val MAX_TRANSCRIPT_CHARS = 2_000
        private const val MAX_CONTENT_LEN = 200

        private const val SYSTEM_PROMPT = """You extract durable personal facts from a transcript of speech.

ALL output is in English. If the transcript is in another language, translate the extracted facts into clear English. Never emit non-English content.

Return ONLY a JSON array. No prose, no markdown, no code fences.

Each element is: {"content": "<short statement>", "kind": "<category>", "topic": "<topic-slug>"}

Rules:
- A durable fact is something the user would want remembered for weeks/months (not "I'm hungry now", not "it's raining today").
- Categories ("kind"): preference, identity, attribute, event, fact, schedule, interest.
- "content" is in third-person English describing the user. e.g., "user prefers Python over Java".
- "topic" is a single hyphenated English slug (e.g., "python", "favourite-colour", "morning-routine"). Always English even if the transcript is in another language.
- Skip transcripts that yield nothing. Return [] in that case.
- Do NOT invent facts; only extract what was clearly stated.
- Do NOT include the source-language text in the output; only the English translation."""
    }
}
