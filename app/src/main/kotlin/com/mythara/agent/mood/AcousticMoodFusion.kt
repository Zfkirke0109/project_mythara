package com.mythara.agent.mood

import com.mythara.secret.observe.acoustic.AcousticAnalyzer

/**
 * Combines a [LexicalMoodScorer] result with an
 * [AcousticAnalyzer] feature set to produce a fused mood label +
 * a confidence-tier hint.
 *
 * Logic is a small decision table — voice-emotion research is messy
 * and per-speaker, but a population-average rule gets us 70% of the
 * way:
 *
 *   text mood   | acoustic                                 → fused
 *   -----------+------------------------------------------+----------
 *   null       | high pitch + high energy + fast rate     → excited
 *   null       | low pitch + low energy + slow rate       → sad
 *   null       | high energy + high pitch (any rate)      → anxious
 *   null       | low energy + slow rate (any pitch)       → sad
 *   null       | high energy + normal pitch + fast        → excited
 *   happy      | high pitch + high energy + fast          → excited (upgrade)
 *   happy      | low energy + slow rate                   → happy (low conf)
 *   sad        | any                                       → sad
 *   frustrated | high energy + fast/normal rate           → frustrated (reinforced)
 *   frustrated | low energy + slow rate                   → frustrated (mild)
 *   anxious    | high pitch + fast rate                   → anxious (reinforced)
 *   excited    | high pitch + high energy                 → excited (reinforced)
 *
 * Tie-break on null acoustic → text wins. When both are null,
 * fused is null too.
 */
object AcousticMoodFusion {

    /**
     * @param lexical from [LexicalMoodScorer.score]; null if text didn't carry signal.
     * @param features from [AcousticAnalyzer.analyze]; null if mic refused capture.
     * @return fused mood label, or null if neither side produced signal.
     */
    fun fuse(lexical: String?, features: AcousticAnalyzer.Features?): String? {
        if (features == null) return lexical
        val pitch = pitchBucket(features.meanF0Hz)
        val energy = energyBucket(features.meanRms)
        val rate = rateBucket(features.wordsPerSec, features.durationSec)

        // Acoustic-only path — used when text was non-emotive.
        if (lexical == null) {
            return acousticOnly(pitch, energy, rate)
        }
        // Text + acoustic fusion.
        return when (lexical) {
            "happy" -> when {
                pitch == "high" && energy == "high" && rate == "fast" -> "excited"
                else -> "happy"
            }
            "sad" -> "sad"
            "frustrated" -> "frustrated"
            "anxious" -> "anxious"
            "excited" -> "excited"
            else -> lexical
        }
    }

    /**
     * Acoustic-only mood resolution, used when the lexical scorer
     * returned null. Conservative — only emits when at least two of
     * (pitch, energy, rate) agree on a direction.
     */
    private fun acousticOnly(pitch: String?, energy: String?, rate: String?): String? {
        // Sad cluster: low everything.
        if ((energy == "low" || pitch == "low") && rate == "slow") return "sad"
        if (energy == "low" && rate == "slow") return "sad"

        // High-arousal cluster: pitch + energy + fast = excited or anxious.
        // Default to "excited" since that's the more common interpretation
        // of "raised voice + fast speech" in normal conversation.
        if ((pitch == "high" || energy == "high") && rate == "fast") return "excited"
        if (energy == "high" && pitch == "high") return "anxious"

        // Otherwise neither pole — return null.
        return null
    }

    private fun pitchBucket(hz: Float): String? = when {
        hz <= 0f -> null
        hz > AcousticAnalyzer.PITCH_HIGH_HZ -> "high"
        hz < AcousticAnalyzer.PITCH_LOW_HZ -> "low"
        else -> "normal"
    }

    private fun energyBucket(rms: Float): String = when {
        rms > AcousticAnalyzer.ENERGY_HIGH -> "high"
        rms < AcousticAnalyzer.ENERGY_LOW -> "low"
        else -> "normal"
    }

    private fun rateBucket(wps: Float, durationSec: Float): String? {
        // <1.5s of audio isn't enough for a meaningful rate signal.
        if (durationSec < 1.5f || wps <= 0f) return null
        return when {
            wps > AcousticAnalyzer.RATE_FAST -> "fast"
            wps < AcousticAnalyzer.RATE_SLOW -> "slow"
            else -> "normal"
        }
    }
}
