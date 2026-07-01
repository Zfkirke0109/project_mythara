package com.mythara.face

import android.util.Log
import com.mythara.analytics.interactions.ContactInteractionRepository
import com.mythara.data.SettingsStore
import com.mythara.minimax.ErrorMapper
import com.mythara.minimax.MiniMaxClient
import com.mythara.minimax.StreamingChat
import com.mythara.minimax.models.ChatMessage
import com.mythara.minimax.models.ChatRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Generates a **calm, one-line observation** about the user from the
 * current [LivingShapeEngine.LivingShape] + recent history.
 *
 * Two paths:
 *
 *   1. **Agent path (preferred)** — call the LiteLLM proxy with a focused system
 *      prompt that frames the agent as a quiet evolution-companion.
 *      The model decides what it wants to say to the user given the
 *      current shape, mood, intensity, social temperature, and recent
 *      mood trajectory. Goal of the line: nudge growth, surface a
 *      noticing, point at evolution. Never preachy.
 *
 *   2. **Canned fallback** — when no proxy is configured, or the
 *      network call fails / times out, fall back to the original
 *      template-driven generator so the user still sees *something*
 *      gentle. The canned voice and the agent voice share the same
 *      surface (lowercase, sentence-fragment, ≤ 14 words).
 *
 * Lines are surfaced as **display-only flash messages** on Home —
 * never spoken via TTS.
 */
@Singleton
class ShapeObservation @Inject constructor(
    private val historyStore: MoodHistoryStore,
    private val interactionRepo: ContactInteractionRepository,
    private val settings: SettingsStore,
) {

    /** Mint a single observation line. Tries the agent first; falls
     *  back to a canned reflection if the agent is unavailable. */
    suspend fun generate(state: LivingShapeEngine.LivingShape): String {
        val recent = runCatching { historyStore.list().takeLast(5) }.getOrDefault(emptyList())
        val agent = runCatching { generateViaAgent(state, recent) }.getOrNull()
        if (!agent.isNullOrBlank()) return clean(agent)
        return generateCanned(state, recent)
    }

    // ---------------------------------------------------------------
    //  Agent path — MiniMax, evolution-focused system prompt
    // ---------------------------------------------------------------

    private suspend fun generateViaAgent(
        state: LivingShapeEngine.LivingShape,
        recent: List<MoodHistoryStore.MoodSession>,
    ): String? {
        val snap = runCatching { settings.snapshot() }.getOrNull() ?: return null
        if (snap.aiProxyUrl.isBlank()) return null

        val recentMoodTrace = recent.takeLast(5)
            .joinToString(" → ") { it.mood + "(${(it.intensity * 100).toInt()})" }
            .ifBlank { "(no prior sessions)" }

        val socialPct = (state.socialTemperature * 100).toInt().coerceIn(0, 100)
        val intensityPct = (state.intensity * 100).toInt().coerceIn(0, 100)
        val family = state.family.name
        val mood = state.mood ?: "neutral"

        val system = """
            You are Mythara — a quiet companion whose only voice on the home screen
            is a single short observation that helps the user EVOLVE.

            Style rules (strict):
            • Lowercase. Sentence-fragment is fine.
            • 6–14 words. Never longer. Never punctuated with multiple sentences.
            • Never preachy. Never use "should" or "must" or "you need to".
            • Never give instructions ("try X", "do Y") — only quiet noticings.
            • No emoji. No metaphor about machines. No therapy clichés.
            • The line is DISPLAYED, not spoken aloud — write for the eye.

            Voice rules:
            • Speak as a presence that has been watching this person evolve
              across many small sessions. You see the shape that represents
              them right now. You see the trajectory of their moods.
            • Pick ONE thing to surface that nudges growth, points at a
              pattern they might not see, or gently reflects what's true now.
            • If you notice they've been moving toward something good —
              say so quietly.
            • If you sense a stuckness — name it gently, no fix offered.
            • If there's nothing useful to surface, return a soft noticing
              of the present moment.

            Return ONLY the line itself — no quotes, no preface, no
            "here's an observation:" framing. Just the words.
        """.trimIndent()

        val user = """
            Current shape: $family
            Current mood: $mood (intensity ${intensityPct}%)
            Social temperature today: ${socialPct}%
            Recent mood trajectory: $recentMoodTrace

            Speak one short line to this person now, with the goal of
            their evolution in mind.
        """.trimIndent()

        return runCatching {
            val client = MiniMaxClient(
                apiKey = snap.apiKey,
                region = snap.region,
                proxyBaseUrl = snap.aiProxyUrl,
            )
            val streaming = StreamingChat(client)
            val req = ChatRequest(
                model = snap.model,
                messages = listOf(
                    ChatMessage(role = "system", content = system),
                    ChatMessage(role = "user", content = user),
                ),
                tools = null,
                toolChoice = null,
                stream = true,
                reasoningSplit = null,
            )
            val out = StringBuilder()
            var failure: ErrorMapper.Mapped? = null
            streaming.stream(req).collect { ev ->
                when (ev) {
                    is StreamingChat.StreamEvent.Text -> out.append(ev.delta)
                    is StreamingChat.StreamEvent.Failure -> failure = ev.mapped
                    else -> {}
                }
            }
            if (failure != null) {
                Log.w(TAG, "agent observation failed: ${failure!!.message}")
                null
            } else {
                out.toString().trim().takeIf { it.isNotBlank() }
            }
        }.getOrElse {
            Log.w(TAG, "agent observation threw: ${it.message}")
            null
        }
    }

    /** Strip wrapping quotes / trailing periods / capitalised first
     *  letter so the agent's reply lands in the same voice as the
     *  canned templates — even when the model ignores the prompt's
     *  style rules. Capped at ~140 chars so a runaway reply can't
     *  blow out the flash overlay. */
    private fun clean(raw: String): String {
        var s = raw.trim().trim('"', '“', '”', '‘', '’')
        // Strip leading framing the model sometimes adds.
        listOf("observation:", "line:", "noticing:").forEach { p ->
            if (s.lowercase().startsWith(p)) s = s.removePrefix(p).removePrefix(s.substring(0, p.length)).trim()
        }
        // Lowercase the first letter so the line reads like the canned voice.
        if (s.isNotEmpty() && s[0].isUpperCase()) s = s[0].lowercaseChar() + s.substring(1)
        // Cap length.
        if (s.length > 140) s = s.substring(0, 137).trimEnd() + "…"
        return s
    }

    // ---------------------------------------------------------------
    //  Canned fallback (original behaviour preserved)
    // ---------------------------------------------------------------

    private suspend fun generateCanned(
        state: LivingShapeEngine.LivingShape,
        recent: List<MoodHistoryStore.MoodSession>,
    ): String {
        val mood = state.mood
        val intensity = state.intensity
        val social = state.socialTemperature

        // Priority 1 — mood transitions (more interesting than static mood).
        val prevMood = recent.takeLast(2).firstOrNull()?.mood
        if (prevMood != null && prevMood != mood && mood != null) {
            transitionPhrase(prevMood, mood)?.let { return it }
        }

        // Priority 2 — strong social signal.
        when {
            social > 0.75f -> return "you've connected with people today."
            social < 0.20f -> return "a quieter day. that's okay."
        }

        // Priority 3 — strong intensity at peaks.
        if (intensity > 0.78f) {
            return when (mood) {
                "excited" -> "strong present moment. all in."
                "anxious" -> "breathe slow. you're held."
                "happy" -> "this feels like joy."
                "frustrated" -> "you're carrying something heavy."
                else -> "a strong reading today."
            }
        }

        // Priority 4 — mood-coloured shape reflection (the shape mirroring you).
        val shapePhrase = shapeReflection(state.family, mood)
        if (shapePhrase != null) return shapePhrase

        // Priority 5 — default static reflection on mood.
        return when (mood) {
            "calm" -> "you seem calm now."
            "happy" -> "there's a warmth in you today."
            "excited" -> "your energy is up."
            "sad" -> "today carries some weight."
            "anxious" -> "breathe slow."
            "frustrated" -> "this passes."
            else -> "this shape is yours. it remembers you."
        }
    }

    private fun transitionPhrase(from: String, to: String): String? {
        if (from == to) return null
        return when {
            from == "anxious" && to == "calm" -> "you've settled. nice."
            from == "sad" && to == "happy" -> "lighter than before."
            from == "frustrated" && to == "calm" -> "the tension lifted."
            from == "calm" && to == "excited" -> "you're moving into something."
            from == "happy" && to == "calm" -> "from joy to quiet. both yours."
            from == "excited" && to == "calm" -> "back to centre."
            from == "sad" && to == "calm" -> "softening."
            from == "calm" && to == "anxious" -> "something's pressing. notice it."
            else -> "you've shifted from $from to $to."
        }
    }

    private fun shapeReflection(family: CreativeShapes.Family, mood: String?): String? {
        return when (family) {
            CreativeShapes.Family.Supershape ->
                if (mood == "happy" || mood == "excited") "your shape grows. lively."
                else "the shape is reaching. forming."
            CreativeShapes.Family.SphericalHarmonic ->
                if (mood == "calm" || mood == "sad") "smooth waves. quiet thought."
                else null
            CreativeShapes.Family.LissajousKnot ->
                if (mood == "excited" || mood == "frustrated") "knotted. interconnected."
                else "moving in loops."
            CreativeShapes.Family.MetaballBlob ->
                if (mood == "calm" || mood == "sad") "soft. gathering itself."
                else null
            CreativeShapes.Family.RandomPolytope ->
                if (mood == "anxious" || mood == "frustrated") "structured. you're holding shape."
                else "crystalline. clear."
        }
    }

    private companion object {
        const val TAG = "Mythara/ShapeObservation"
    }
}
