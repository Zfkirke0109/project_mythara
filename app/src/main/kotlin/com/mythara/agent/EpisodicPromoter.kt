package com.mythara.agent

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.extract.SemanticExtractor
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import com.mythara.secret.observe.vault.LearningDao
import com.mythara.secret.observe.vault.LearningEntity
import com.mythara.secret.observe.vault.LearningVault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compresses ephemeral working-tier transcripts into durable
 * episodic-tier summaries via Gemma. The M8.3 part 3 piece of
 * SelfOrganizer.
 *
 * Pipeline per run:
 *   1. List all working-tier records added since the last promotion
 *      run that carry a (USE-Lite) embedding.
 *   2. Greedy single-pass cluster by embedding cosine similarity —
 *      transcripts about the same topic in the same rough time window
 *      collapse into one cluster.
 *   3. For each cluster ≥ [MIN_CLUSTER_SIZE], concatenate the
 *      constituent transcripts and ask Gemma to produce a single
 *      short third-person summary.
 *   4. Persist the summary as a tier-`e` record with
 *      `src = self-organiser:gemma-summary`, ref back-linking to the
 *      cluster's working-record IDs. Dedup happens naturally via the
 *      sha index — if the same cluster is re-summarised verbatim we
 *      reinforce instead of duplicate.
 *   5. Stamp the run timestamp so the next call only sees newer
 *      working records.
 *
 * Why this is its own class and not inline in SelfOrganizerWorker:
 *   - The worker also dedups + (future) demotes stale records. Each
 *     pass is independent; isolating promotion keeps WorkManager's
 *     try-once-fail-once retry from re-running expensive Gemma
 *     summarisation when only dedup was at fault.
 *   - Future: callable from a "promote now" button in Secret Settings
 *     for testing without waiting for the nightly tick.
 *
 * Gemma-gated: if [SemanticExtractor.gemmaEnabledFlow] is off OR the
 * model isn't loaded, the promoter no-ops (returns an empty Report).
 * Heuristic-only Observe stays at working tier — no fake summaries.
 */
@Singleton
class EpisodicPromoter @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: LearningDao,
    private val vault: LearningVault,
    private val gemma: GemmaExtractor,
    private val embedder: LocalEmbedder,
    private val semanticExtractor: SemanticExtractor,
) {
    data class Report(
        val workingSeen: Int,
        val clustersFound: Int,
        val episodicCreated: Int,
        val skippedReason: String? = null,
    )

    private val Context.ds: DataStore<Preferences>
        by preferencesDataStore("mythara_episodic_promoter")
    private val keyLastRunMs = longPreferencesKey("lastRunMs")

    suspend fun promote(): Report {
        if (!gemma.isReady()) {
            Log.d(TAG, "skip: Gemma model not on disk")
            return Report(0, 0, 0, "gemma-model-missing")
        }
        if (!semanticExtractor.gemmaEnabledFlow().first()) {
            Log.d(TAG, "skip: Gemma extraction disabled (not probed yet)")
            return Report(0, 0, 0, "gemma-disabled")
        }

        val since = ctx.ds.data.first()[keyLastRunMs] ?: 0L
        val working = dao.listByTierSince(
            tier = Tier.Working.code,
            sinceMs = since,
            limit = MAX_RECORDS,
        )
        if (working.isEmpty()) {
            Log.d(TAG, "skip: no new working records since ${since}")
            return Report(0, 0, 0, "no-new-records")
        }

        val clusters = clusterByEmbedding(working, CLUSTER_THRESHOLD)
        Log.d(TAG, "found ${clusters.size} cluster(s) from ${working.size} working records")
        var created = 0
        val now = System.currentTimeMillis()

        // Bound the number of Gemma summaries per run — each call is
        // ~1-2 sec on Tensor G4. Don't burn 100s of seconds of native
        // inference inside a single WorkManager tick.
        for (cluster in clusters.take(MAX_SUMMARIES_PER_RUN)) {
            if (cluster.size < MIN_CLUSTER_SIZE) continue
            val concatenated = cluster.joinToString("\n---\n") { it.content }
            val summary = gemma.summarise(concatenated) ?: continue
            val summaryEmbedding = if (embedder.isReady()) {
                runCatching { embedder.embed(summary) }.getOrNull()
            } else null

            // Aggregate facets the cluster carries — most-common mood,
            // most-common speaker. Keeps episodic queryable by the
            // same dimensions as the underlying working records.
            val facets = buildList {
                add("kind:episodic-summary")
                add("cluster-size:${cluster.size}")
                dominantFacetPrefix(cluster, "mood:")?.let { add(it) }
                dominantFacetPrefix(cluster, "speaker:")?.let { add(it) }
            }
            val refs = "cluster:" + cluster.take(MAX_REFS_PER_CLUSTER).joinToString(",") { it.id }

            val added = vault.add(
                content = summary,
                tier = Tier.Episodic,
                src = "self-organiser:gemma-summary",
                facets = facets,
                embedding = summaryEmbedding,
                embModel = if (summaryEmbedding != null) EmbeddingsModelStore.MODEL_ID else null,
                ref = refs.take(MAX_REF_LEN),
                conf = 0.8,
                now = now,
            )
            if (added) {
                created++
                Log.d(TAG, "episodic created (cluster size=${cluster.size}): ${summary.take(80)}")
            }
        }

        ctx.ds.edit { it[keyLastRunMs] = now }
        return Report(
            workingSeen = working.size,
            clustersFound = clusters.size,
            episodicCreated = created,
        )
    }

    /**
     * Single-pass greedy cluster: for each record, find the cluster
     * whose centroid is most similar; if above threshold, join it,
     * else start a new cluster. Order matters — feeding records in
     * timestamp order tends to group chronologically-near transcripts
     * about the same topic, which is the natural meaning of
     * "episodic" anyway.
     */
    private fun clusterByEmbedding(
        records: List<LearningEntity>,
        threshold: Float,
    ): List<List<LearningEntity>> {
        if (records.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<LearningEntity>>()
        // Decode once, cache as we go.
        val recordVecs: List<FloatArray> = records.map { entity ->
            LocalEmbedder.decode(entity.embedding!!)
        }
        val centroids = mutableListOf<FloatArray>()

        for ((i, record) in records.withIndex()) {
            val vec = recordVecs[i]
            var bestIdx = -1
            var bestSim = threshold
            for ((cIdx, centroid) in centroids.withIndex()) {
                val sim = LocalEmbedder.cosine(vec, centroid)
                if (sim > bestSim) {
                    bestSim = sim
                    bestIdx = cIdx
                }
            }
            if (bestIdx >= 0) {
                clusters[bestIdx].add(record)
                centroids[bestIdx] = mergedCentroid(centroids[bestIdx], vec, clusters[bestIdx].size)
            } else {
                clusters.add(mutableListOf(record))
                centroids.add(vec.copyOf())
            }
        }
        return clusters
    }

    /** Rolling-average centroid update — O(dim) per add, no full re-scan. */
    private fun mergedCentroid(centroid: FloatArray, newVec: FloatArray, newSize: Int): FloatArray {
        val out = FloatArray(centroid.size)
        val prevSize = newSize - 1
        for (i in centroid.indices) {
            out[i] = (centroid[i] * prevSize + newVec[i]) / newSize
        }
        return out
    }

    /**
     * Find the most-common value of a facet prefix across a cluster.
     * Returns "<prefix><value>" suitable for adding to a new facet
     * list, or null when no value owns ≥ [DOMINANT_FRACTION] of
     * tagged records.
     */
    private fun dominantFacetPrefix(cluster: List<LearningEntity>, prefix: String): String? {
        val values = cluster.mapNotNull { entity ->
            vault.decodeFacets(entity)
                .firstOrNull { it.startsWith(prefix) }
                ?.removePrefix(prefix)
        }
        if (values.isEmpty()) return null
        val histogram = values.groupingBy { it }.eachCount()
        val (top, count) = histogram.maxBy { it.value }
        return if (count.toDouble() / values.size >= DOMINANT_FRACTION) "$prefix$top" else null
    }

    companion object {
        private const val TAG = "Mythara/Episodic"

        /** Working records considered per run. Caps inference time. */
        const val MAX_RECORDS = 500

        /** Hard upper bound on Gemma summarisation calls per run. */
        const val MAX_SUMMARIES_PER_RUN = 20

        /** Minimum cluster size to bother summarising — singletons stay working. */
        const val MIN_CLUSTER_SIZE = 2

        /** Cosine similarity floor for cluster membership. */
        const val CLUSTER_THRESHOLD = 0.55f

        /** Ref-list cap so giant clusters don't blow out a `ref` string. */
        const val MAX_REFS_PER_CLUSTER = 30
        const val MAX_REF_LEN = 1200

        /** Same dominance threshold as SemanticRecall — be conservative. */
        const val DOMINANT_FRACTION = 0.5
    }
}
