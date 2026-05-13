package com.mythara.secret.observe.extract

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Heuristic transcript → semantic-fact extractor. M8.2.0 stub; replaced
 * by MediaPipe Gemma 2B in M8.2.1.
 *
 * The patterns are deliberately conservative — over-extraction floods
 * the vault with low-value rows and dilutes any future retrieval
 * relevance. Today we lift:
 *   - explicit personal preferences ("I like / love / prefer X")
 *   - explicit anti-preferences ("I don't like / hate X")
 *   - simple identity claims ("I am / I'm a/an X")
 *   - "My X is Y" attribute statements
 *
 * Each extracted fact gets:
 *   - `tier = "s"`         (semantic)
 *   - `src  = "extract:heuristic"`
 *   - `conf = 0.5`         (low, because heuristic — Gemma will produce 0.8+)
 *   - facets like `kind:preference`, `kind:identity`, `polarity:negative`, …
 */
@Singleton
class LearningExtractor @Inject constructor() {

    data class Extracted(
        val content: String,
        val facets: List<String>,
        val conf: Double = 0.5,
    )

    fun extract(transcript: String): List<Extracted> {
        if (transcript.isBlank()) return emptyList()
        val out = mutableListOf<Extracted>()
        val sentences = transcript.split(SENTENCE_BOUNDARY).map { it.trim() }.filter { it.length >= MIN_LEN }

        for (sentence in sentences) {
            val lower = sentence.lowercase()
            when {
                NEG_PREF.containsMatchIn(lower) -> NEG_PREF.find(lower)?.let { m ->
                    val obj = m.groupValues[2].trim()
                    if (obj.isNotBlank() && obj.length <= MAX_OBJ_LEN) {
                        out.add(Extracted(
                            content = "user dislikes: $obj",
                            facets = listOf("kind:preference", "polarity:negative", "topic:${slug(obj)}"),
                        ))
                    }
                }
                POS_PREF.containsMatchIn(lower) -> POS_PREF.find(lower)?.let { m ->
                    val obj = m.groupValues[2].trim()
                    if (obj.isNotBlank() && obj.length <= MAX_OBJ_LEN) {
                        out.add(Extracted(
                            content = "user likes: $obj",
                            facets = listOf("kind:preference", "polarity:positive", "topic:${slug(obj)}"),
                        ))
                    }
                }
                IDENTITY.containsMatchIn(lower) -> IDENTITY.find(lower)?.let { m ->
                    val obj = m.groupValues[2].trim()
                    if (obj.isNotBlank() && obj.length <= MAX_OBJ_LEN) {
                        out.add(Extracted(
                            content = "user identifies as: $obj",
                            facets = listOf("kind:identity", "topic:${slug(obj)}"),
                        ))
                    }
                }
                ATTRIBUTE.containsMatchIn(lower) -> ATTRIBUTE.find(lower)?.let { m ->
                    val key = m.groupValues[1].trim()
                    val value = m.groupValues[2].trim()
                    if (key.isNotBlank() && value.isNotBlank() && value.length <= MAX_OBJ_LEN) {
                        out.add(Extracted(
                            content = "$key: $value",
                            facets = listOf("kind:attribute", "topic:${slug(key)}"),
                        ))
                    }
                }
            }
        }
        return out.distinctBy { it.content }
    }

    private fun slug(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "misc" }

    companion object {
        private val SENTENCE_BOUNDARY = Regex("""[.!?]\s+""")
        private const val MIN_LEN = 4
        private const val MAX_OBJ_LEN = 80
        private val POS_PREF = Regex("""\b(i (?:really )?(?:like|love|enjoy|prefer))\s+([a-z0-9 ,'\-]+)""")
        private val NEG_PREF = Regex("""\b(i (?:really )?(?:don't|do not|hate|dislike)(?: like)?)\s+([a-z0-9 ,'\-]+)""")
        private val IDENTITY = Regex("""\b(i am(?: an?)?|i'm(?: an?)?)\s+([a-z0-9 ,'\-]+)""")
        private val ATTRIBUTE = Regex("""\bmy ([a-z0-9 \-]{2,30}?) is ([a-z0-9 ,'\-]{2,80})""")
    }
}
