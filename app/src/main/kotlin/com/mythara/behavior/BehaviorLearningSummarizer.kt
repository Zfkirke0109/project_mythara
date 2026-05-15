package com.mythara.behavior

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mythara.ai.ModelRouter
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningDao
import com.mythara.secret.observe.vault.LearningEntity
import com.mythara.secret.observe.vault.LearningVault
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Periodic worker that turns the day's behaviour-event vault rows
 * (and adjacent health snapshots) into a single LLM-summarised
 * "what we learned about you today" record, then **deletes the raw
 * transcripts it summarised**.
 *
 * This is the bridge from the day-one storage substrate
 * ([BehaviorEventStore]) into the user's persistent self-portrait:
 * raw behaviour rows are noisy, dense, and high-volume; the daily
 * summary is the durable artifact the agent recalls when deciding
 * future interventions.
 *
 * Pipeline (per run):
 *   1. Pull every behaviour event whose facet starts with
 *      `behavior:` from the past 24 h.
 *   2. Pull the most recent `topic:health` snapshot (HR, sleep,
 *      steps, weight) so the LLM can correlate behaviour with
 *      physiological context.
 *   3. Format both into a compact prompt + ask
 *      [ModelRouter.summarise] with `heavy = true` (uses MiniMax
 *      first, falls back to local Gemma).
 *   4. Write the summary as a single Tier.Episodic vault row
 *      (src=`behavior:daily-summary`, facets include the YYYY-MM-DD
 *      date so future queries can pull "last 7 days of summaries").
 *   5. **Delete every raw row that fed into the summary** via
 *      [LearningDao.deleteByFacet]. The summary is now the durable
 *      memory; raw transcripts go.
 *
 * Privacy + storage discipline:
 *   - The summary lives in the same vault every other Mythara
 *     observation lives in — same scrubbing (SecretScrubber), same
 *     opt-in GitHub sync.
 *   - Raw deletion is intentional: the user explicitly asked for
 *     "transcripts to be deleted after learning is generated".
 *     We only delete AFTER vault.add of the summary returns true,
 *     so a model failure leaves the raw data intact for the next run.
 *
 * Schedule: invoked from MytharaApp's WorkManager bootstrap as a
 * periodic worker. Cadence is configurable; ~6 h is a reasonable
 * default (catches behaviour patterns within the same day so the
 * agent can react before the user's context changes).
 */
@HiltWorker
class BehaviorLearningSummarizer @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val vault: LearningVault,
    private val dao: LearningDao,
    private val router: ModelRouter,
) : CoroutineWorker(appContext, params) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val cutoff = now - WINDOW_MS
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

        // 1. Behaviour events in the window.
        val window = runCatching { dao.listBetween(cutoff, now, limit = 500) }
            .getOrNull()
            .orEmpty()
        val behaviourRows = window.filter { row ->
            row.src.startsWith("behavior:") ||
                decodeFacets(row.facets).any { it == BehaviorEventStore.FACET_KIND }
        }
        if (behaviourRows.isEmpty()) {
            Log.d(TAG, "no behaviour events in last ${WINDOW_MS / 3600_000}h — skipping")
            return Result.success()
        }

        // 2. Most recent health snapshot (1 row, latest).
        val healthSnapshot = runCatching { dao.listRecent(limit = 80) }
            .getOrNull()
            .orEmpty()
            .firstOrNull { row ->
                decodeFacets(row.facets).any { it == "topic:health" }
            }
        val healthLine = healthSnapshot?.content?.let { compactHealth(it) }

        // 3. Build the prompt + run heavy summarisation.
        val prompt = buildPrompt(behaviourRows, healthLine, date)
        val summary = runCatching {
            router.summarise(prompt, maxLen = 600, heavy = true)
        }.getOrNull()
        if (summary.isNullOrBlank()) {
            Log.w(TAG, "ModelRouter.summarise returned null/blank — keeping raw rows for retry")
            return Result.retry()
        }

        // 4. Write the summary.
        val written = vault.add(
            content = summary,
            tier = Tier.Episodic,
            src = "behavior:daily-summary",
            facets = listOf(
                BehaviorEventStore.FACET_KIND,
                "behavior:daily-summary",
                "date:$date",
                "summary:behaviour",
            ),
            conf = 0.9,
        )
        if (!written) {
            Log.w(TAG, "vault.add(summary) failed — keeping raw rows for retry")
            return Result.retry()
        }

        // 5. Delete the raw transcripts now that the summary
        //    persists. Each behaviour-event row carries
        //    [BehaviorEventStore.FACET_KIND] — one DELETE clears
        //    them all in a single SQL hit.
        //
        //    We do NOT delete the health snapshot — it's its own
        //    domain (managed by HealthLearningWorker) and the
        //    behaviour summary just borrowed it for context.
        val deleted = runCatching { dao.deleteByFacet(BehaviorEventStore.FACET_KIND) }
            .getOrDefault(0)
        Log.i(
            TAG,
            "behaviour summary written for $date (${behaviourRows.size} raw rows " +
                "in window, $deleted total rows deleted via facet match)",
        )

        return Result.success()
    }

    private fun buildPrompt(
        rows: List<LearningEntity>,
        healthLine: String?,
        date: String,
    ): String {
        val sb = StringBuilder()
        sb.append("You are summarising one user's behaviour events from the last 24 hours ")
        sb.append("for a personal-AI assistant's daily-review log. Today's date: $date. ")
        sb.append("Read the events below and write a CONCISE markdown summary (≤ 6 short ")
        sb.append("bullets, plain English) covering:\n")
        sb.append("  - Patterns observed (e.g. 'missed reminders cluster around late-evening ")
        sb.append("with reason: tired')\n")
        sb.append("  - Likely root causes (sleep deficit? overbooked calendar? work focus?)\n")
        sb.append("  - One specific intervention to suggest tomorrow (e.g. 'protect 30 min of ")
        sb.append("wind-down before 10pm')\n")
        if (healthLine != null) {
            sb.append("  - Correlate with the health context where it matters\n")
        }
        sb.append("\n--- BEHAVIOUR EVENTS ---\n")
        for (row in rows.sortedBy { it.tsMillis }) {
            val ts = SimpleDateFormat("HH:mm", Locale.US).format(Date(row.tsMillis))
            sb.append("[$ts] ${row.src}: ${row.content.take(200)}\n")
        }
        if (healthLine != null) {
            sb.append("\n--- LATEST HEALTH SNAPSHOT ---\n")
            sb.append(healthLine).append('\n')
        }
        sb.append("\n--- END ---\nReturn ONLY the markdown summary, no preamble.\n")
        return sb.toString()
    }

    /** Pluck the high-signal numbers out of a HealthLearningWorker
     *  snapshot row so we don't dump the full JSON into the prompt. */
    private fun compactHealth(snapshotJson: String): String? = runCatching {
        val obj = json.parseToJsonElement(snapshotJson).jsonObject
        val parts = mutableListOf<String>()
        obj["resting_hr_bpm"]?.jsonPrimitive?.contentOrNull?.let { parts.add("resting HR $it bpm") }
        obj["avg_hr_24h_bpm"]?.jsonPrimitive?.contentOrNull?.let { parts.add("avg HR ${it} bpm") }
        obj["sleep_minutes"]?.jsonPrimitive?.contentOrNull?.let {
            val mins = it.toIntOrNull() ?: return@let
            parts.add("slept ${mins / 60}h ${mins % 60}m")
        }
        obj["steps_today"]?.jsonPrimitive?.contentOrNull?.let { parts.add("$it steps today") }
        obj["weight_kg"]?.jsonPrimitive?.contentOrNull?.let { parts.add("$it kg") }
        if (parts.isEmpty()) null else parts.joinToString(", ")
    }.getOrNull()

    private fun decodeFacets(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "Mythara/BehaviorSummarizer"

        /** Window of behaviour events fed into a single summary —
         *  24 h matches the "daily review" framing. */
        const val WINDOW_MS = 24L * 60 * 60 * 1000

        /** WorkManager unique name. */
        const val UNIQUE_NAME = "mythara_behavior_summary"
    }
}

// JsonElement.jsonObject is in kotlinx-serialization-json:
private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = (this as kotlinx.serialization.json.JsonObject)

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (isString || content != "null") content else null
