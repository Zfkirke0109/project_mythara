package com.mythara.face

import android.content.Context
import com.mythara.analytics.interactions.ContactInteractionRepository
import com.mythara.branding.MoodSink
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import com.mythara.ui.face.ParticleShapes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Central state holder for the **living shape** on Home — the
 * geometric form that lives in the face mesh and evolves
 * continuously based on every signal Mythara can read about the
 * user:
 *
 *   - face expression (smile, eyes) via [EmotionDetector]
 *   - heart rate vs personal baseline via LiveWallpaperPulseSink
 *   - voice tone (text + audio) via ChatMoodTracker /
 *     AcousticMoodFusion (publish to [EmotionDetector.publishVoiceTone])
 *   - **relationship temperature** — how many real interactions
 *     with real people happened in the recent window; refreshed
 *     periodically by reading [ContactInteractionRepository]
 *
 * The state is one [LivingShape] data object held in a StateFlow.
 * FaceMesh reads it; updates happen continuously as signals
 * arrive. No more session-bounded re-rolls without smooth
 * transitions — every signal change nudges parameters in real
 * time, and the shape kind only flips on actual face-detect
 * transitions (preserving the "never repeat from old" guarantee).
 *
 * Memory: on every face-detect session end (face leaves), the
 * dominant state is written to [LearningVault] as a tagged
 * record so the agent's recall surface knows the user has been
 * (e.g.) calm and connected this afternoon. This feeds the
 * "evolve with memory and learning" loop the user asked for.
 */
@Singleton
class LivingShapeEngine @Inject constructor(
    private val emotionDetector: EmotionDetector,
    private val historyStore: MoodHistoryStore,
    private val interactionRepo: ContactInteractionRepository,
    private val vault: LearningVault,
    @ApplicationContext private val ctx: Context,
) {

    data class LivingShape(
        val kind: ParticleShapes.Kind,
        /** Unit vector — the axis the shape spins around this session.
         *  Re-rolled on every new pickup so a different geometry tilts
         *  in a different direction. */
        val rotationAxis: FloatArray,
        val rotationRateHz: Float,
        val glowMultiplier: Float,
        val particleCount: Int,
        /** Most recent detector reading (mood label + intensity). May
         *  be null until a face has been seen at least once. */
        val mood: String?,
        val intensity: Float,
        /** 0 (solitary day) → 1 (richly social day) computed from the
         *  ContactInteractionDb. Tints the shape toward warm hues +
         *  denser particles when high; cool + sparser when low. */
        val socialTemperature: Float,
        /** True while a face is currently in frame. When false the
         *  FaceMesh holds the LAST assembled shape but at lower
         *  energy (slower rotation, dimmer glow) — the shape PERSISTS
         *  rather than scattering apart. */
        val active: Boolean,
        val sessionStartMs: Long,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LivingShape) return false
            return kind == other.kind &&
                rotationAxis.contentEquals(other.rotationAxis) &&
                rotationRateHz == other.rotationRateHz &&
                glowMultiplier == other.glowMultiplier &&
                particleCount == other.particleCount &&
                mood == other.mood && intensity == other.intensity &&
                socialTemperature == other.socialTemperature &&
                active == other.active && sessionStartMs == other.sessionStartMs
        }
        override fun hashCode(): Int {
            var r = kind.hashCode()
            r = 31 * r + rotationAxis.contentHashCode()
            r = 31 * r + rotationRateHz.hashCode()
            r = 31 * r + glowMultiplier.hashCode()
            r = 31 * r + particleCount
            r = 31 * r + (mood?.hashCode() ?: 0)
            r = 31 * r + intensity.hashCode()
            r = 31 * r + socialTemperature.hashCode()
            r = 31 * r + active.hashCode()
            r = 31 * r + sessionStartMs.hashCode()
            return r
        }
    }

    private val initial = LivingShape(
        kind = ParticleShapes.Kind.Icosahedron,
        rotationAxis = floatArrayOf(0f, 1f, 0f),
        rotationRateHz = 0.30f,
        glowMultiplier = 1.0f,
        particleCount = 600,
        mood = null,
        intensity = 0.4f,
        socialTemperature = 0.3f,
        active = false,
        sessionStartMs = 0L,
    )

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<LivingShape> = _state.asStateFlow()

    /** Sliding window of the last 4 shape kinds — feeds
     *  [ShapeMoodMapping.pickShape]'s never-repeat guarantee. */
    private val recentKinds = ArrayDeque<ParticleShapes.Kind>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Continuous parameter drift driven by EmotionDetector readings.
        scope.launch {
            emotionDetector.reading.collect { onReadingChange(it) }
        }
        // Periodic social-temperature refresh — every minute is plenty
        // because interaction events arrive at chat / call / SMS pace,
        // not per-frame.
        scope.launch {
            while (true) {
                refreshSocialTemperature()
                delay(60_000L)
            }
        }
    }

    /** Called by FaceMesh on every face-detect session start
     *  (pose.present transition false → true). Re-picks the shape
     *  with the never-repeat guarantee + records the start time so
     *  the session-end hook knows the duration. */
    suspend fun startSession() {
        val rnd = Random(System.nanoTime())
        val mood = MoodSink.current()
        val intensity = emotionDetector.reading.value?.intensity ?: 0.5f
        val recentMoods = runCatching { historyStore.recentMoods() }
            .getOrDefault(emptyList())
        val kind = ShapeMoodMapping.pickShape(
            mood = mood,
            recentHistory = recentMoods,
            recentShapes = recentKinds.toList(),
            rnd = rnd,
        )
        // Slide the new pick onto the recent window.
        recentKinds.addFirst(kind)
        while (recentKinds.size > 4) recentKinds.removeLast()

        val axis = FloatArray(3)
        ParticleShapes.randomUnitVector(rnd, axis)
        _state.value = _state.value.copy(
            kind = kind,
            rotationAxis = axis,
            rotationRateHz = ShapeMoodMapping.rotationRateHz(mood, intensity),
            glowMultiplier = ShapeMoodMapping.glowMultiplier(mood, intensity),
            particleCount = ShapeMoodMapping.particleCount(kind, mood, intensity),
            mood = mood,
            intensity = intensity,
            active = true,
            sessionStartMs = System.currentTimeMillis(),
        )
    }

    /** Called by FaceMesh on session end (pose.present transition
     *  true → false). Holds the shape kind + axis, drops the energy
     *  parameters (slower spin, dimmer glow), and writes a memory
     *  record to [MoodHistoryStore] + [LearningVault]. */
    suspend fun endSession() {
        val cur = _state.value
        if (cur.sessionStartMs <= 0L) return
        val durationMs = (System.currentTimeMillis() - cur.sessionStartMs).coerceAtLeast(0L)
        // Drop to idle energy but KEEP the shape — the user wants the
        // last evolved form to persist when the face leaves.
        _state.value = cur.copy(
            active = false,
            rotationRateHz = cur.rotationRateHz * 0.45f,
            glowMultiplier = cur.glowMultiplier * 0.55f,
        )
        // Filter out flicker events.
        if (durationMs < 500L) return
        // 1. Mood history — drives the next shape pick's history bias.
        runCatching {
            historyStore.record(
                MoodHistoryStore.MoodSession(
                    tsMs = cur.sessionStartMs,
                    mood = cur.mood ?: "calm",
                    intensity = cur.intensity,
                    durationMs = durationMs,
                    shapeKind = cur.kind.name,
                ),
            )
        }
        // 2. Memory of me — into the LearningVault as a tagged
        //    observation. The agent's recall surface can find these
        //    when the user asks "how was I yesterday?" or the
        //    persona builder rolls them up.
        val durationSec = (durationMs / 1000L).coerceAtLeast(1L)
        val content = buildString {
            append("self emotional reading: ")
            append(cur.mood ?: "calm")
            append(" (intensity ").append("%.2f".format(cur.intensity)).append(")")
            append(" for ").append(durationSec).append("s")
            append(", shape=").append(cur.kind.name.lowercase())
            append(", social=").append("%.2f".format(cur.socialTemperature))
        }
        runCatching {
            vault.add(
                content = content,
                tier = Tier.Semantic,
                src = "living-shape",
                facets = listOf(
                    "kind:emotional-session",
                    "mood:${cur.mood ?: "calm"}",
                    "shape:${cur.kind.name.lowercase()}",
                    "target:self",
                    "social:${(cur.socialTemperature * 10).toInt()}",
                ),
                conf = cur.intensity.toDouble().coerceAtLeast(0.3),
            )
        }
    }

    /** Continuous parameter drift driven by every EmotionDetector
     *  reading (face frame ticks come at ~30 fps when tracking, ~3
     *  fps when idle). EMA on rotation rate + glow keeps the
     *  shape from twitching as the smile or HR signal jitters. */
    private fun onReadingChange(reading: EmotionDetector.Reading?) {
        val cur = _state.value
        val mood = reading?.mood ?: cur.mood
        val intensity = reading?.intensity ?: cur.intensity
        val targetRate = ShapeMoodMapping.rotationRateHz(mood, intensity)
        val targetGlow = ShapeMoodMapping.glowMultiplier(mood, intensity)
        // Strong smoothing — don't want every face-jitter to bounce
        // the visible rotation rate around. 12 % per update means
        // ~30 readings (~1 s) for a full transition.
        val newRate = cur.rotationRateHz * 0.88f + targetRate * 0.12f
        val newGlow = cur.glowMultiplier * 0.88f + targetGlow * 0.12f
        // Particle count uses the freshly-picked kind for THIS session;
        // we deliberately don't change particleCount mid-session to
        // avoid jarring density shifts. It re-rolls on the next start.
        _state.value = cur.copy(
            rotationRateHz = newRate,
            glowMultiplier = newGlow,
            mood = mood,
            intensity = intensity,
        )
    }

    /** Pull recent interaction counts from the ContactInteractionDb
     *  and turn them into a 0..1 social-temperature signal. The shape
     *  reads this to tilt density + colour energy when the user has
     *  been actively engaging with people vs solo. */
    private suspend fun refreshSocialTemperature() {
        val warmth = runCatching {
            val now = System.currentTimeMillis()
            val window = 24L * 3_600_000L // 24 h
            val rows = interactionRepo.dao.listAll(limit = 200)
                .filter { now - it.tsMs < window }
            // Recency-weighted count: an interaction now counts ~1,
            // 12 h ago ~0.5, 24 h ago ~0.05. Normalise so 20
            // recency-weighted interactions / day reads as "max
            // warmth" — empirically this matches a normal social day.
            var weighted = 0f
            for (row in rows) {
                val ageH = ((now - row.tsMs) / 3_600_000f).coerceAtLeast(0.001f)
                weighted += (1f / (1f + ageH * 0.15f))
            }
            (weighted / 20f).coerceIn(0f, 1f)
        }.getOrDefault(_state.value.socialTemperature)
        // Smooth so the bar doesn't lurch on every refresh tick.
        val cur = _state.value
        val smoothed = cur.socialTemperature * 0.7f + warmth * 0.3f
        _state.value = cur.copy(socialTemperature = smoothed)
    }
}
