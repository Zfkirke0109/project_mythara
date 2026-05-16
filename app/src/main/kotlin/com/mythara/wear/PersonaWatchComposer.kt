package com.mythara.wear

import com.mythara.agent.SemanticRecall
import com.mythara.branding.MoodSink
import com.mythara.branding.MoodVisualMapping
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composes a 60-char watch-face line summarising the user's current
 * emotional + dispositional state, and pushes it through
 * [WatchInsightPusher] so it lands on the Mythara Tactical face's
 * insight complication.
 *
 * Phase J of the Capability Expansion v2 plan. The watch already
 * had an insight slot ([InsightComplicationService]); previously it
 * was driven by health-derived insights ad-hoc. This adds a
 * per-turn persona/mood snapshot composer that runs alongside the
 * other post-turn extractors in [com.mythara.agent.AgentRunner].
 *
 * Format examples (≤ 60 chars):
 *   • "calming · anxious · work"
 *   • "ambient · neutral · sleep"
 *   • "warming · sad · money"
 *   • "Mythara online" (fallback when no mood signal exists)
 *
 * Push deduplication: cached the last-pushed line in-memory and
 * skips push when it hasn't changed, so we don't burn watch
 * battery on identical-payload Bluetooth round-trips.
 */
@Singleton
class PersonaWatchComposer @Inject constructor(
    private val pusher: WatchInsightPusher,
    private val vault: LearningVault,
    private val recall: SemanticRecall,
) {
    @Volatile private var lastPushed: String? = null

    /**
     * Build the line + push to the watch. Safe to call from any
     * coroutine; suspends only for vault reads.
     */
    suspend fun pushNow() {
        val line = compose() ?: return
        if (line == lastPushed) return
        lastPushed = line
        pusher.push(line)
    }

    /** Force the next push regardless of dedupe — used by callers
     *  that want to be sure the watch refreshes (e.g. user opened
     *  the watch face and the cached line is stale). */
    fun invalidateDedupe() {
        lastPushed = null
    }

    private suspend fun compose(): String? {
        val mood = recall.currentMood() ?: recall.recentMoodTrend() ?: MoodSink.current()
        val intervention = MoodVisualMapping.forMood(mood).label

        // Pull the most-reinforced active concern, if any. Cheap:
        // single Semantic-tier scan, filter, group, max.
        val topConcern = runCatching {
            vault.listByTier(Tier.Semantic, limit = 150)
        }.getOrDefault(emptyList())
            .filter { entity ->
                val f = vault.decodeFacets(entity)
                "kind:trait" in f && "target:self" in f && "dim:concern" in f
            }
            .flatMap { entity ->
                vault.decodeFacets(entity)
                    .filter { it.startsWith("topic:") }
                    .map { it.removePrefix("topic:") to entity.seen }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, seens) -> seens.sum() }
            .entries.maxByOrNull { it.value }
            ?.key
            ?.take(MAX_CONCERN_CHARS)

        val moodLabel = mood?.take(MAX_MOOD_CHARS) ?: "neutral"

        val parts = buildList {
            add(intervention)
            add(moodLabel)
            if (!topConcern.isNullOrBlank()) add(topConcern)
        }
        val line = parts.joinToString(" · ").take(MAX_LINE_CHARS)
        return line.ifBlank { null }
    }

    companion object {
        private const val MAX_LINE_CHARS = 60
        private const val MAX_MOOD_CHARS = 12
        private const val MAX_CONCERN_CHARS = 16
    }
}
