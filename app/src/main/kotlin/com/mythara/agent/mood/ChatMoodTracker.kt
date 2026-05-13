package com.mythara.agent.mood

import android.util.Log
import com.mythara.memory.Tier
import com.mythara.secret.observe.acoustic.AcousticAnalyzer
import com.mythara.secret.observe.vault.LearningVault
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks up an emotional signal from each user turn and persists it
 * as a working-tier vault record. The agent loop's [SemanticRecall]
 * reads the vault on the very next request build, so the just-
 * detected mood biases the same turn's reply tone — TTS pitch +
 * rate, ElevenLabs stability + style, AND the agent's "be warm"
 * system-message framing all see the up-to-date trend.
 *
 * Two paths:
 *  - `track(text, fromVoice)` — the fast lexical scorer. Sub-ms,
 *    no model deps. Called before every [AgentRunner.submit] so
 *    the current turn's TTS prosody reflects the just-typed mood.
 *  - (Future) async Gemma re-extraction for stronger signal —
 *    runs in the background, replaces the lexical record's
 *    confidence when it lands.
 *
 * Why not use the existing Observe-side mood extractor: that one
 * runs on transcripts captured by the always-listening pipeline.
 * If Observe is off (default for many users), the vault would
 * never see chat mood, and the agent's tone would stay flat even
 * when the user is clearly anxious / frustrated. This class fills
 * that gap.
 *
 * Privacy: lexical mood is derived from text the user already sent
 * to MiniMax; persisting "mood:sad" + the source text excerpt
 * doesn't leak anything new. Records live in the same vault that
 * already syncs to the user's GitHub backup.
 */
@Singleton
class ChatMoodTracker @Inject constructor(
    private val vault: LearningVault,
    private val acoustic: AcousticAnalyzer,
) {
    /**
     * Score [text], persist as a vault working-tier record, return
     * the detected mood (or null) for caller convenience. Never
     * throws; vault write failures log + return null.
     *
     * @param fromVoice when true, the source facet is `chat:voice`
     *   so downstream queries can filter spoken vs typed mood.
     */
    suspend fun track(text: String, fromVoice: Boolean): String? {
        val mood = LexicalMoodScorer.score(text) ?: return null
        val src = if (fromVoice) "chat:voice" else "chat:typed"
        val excerpt = text.take(MAX_EXCERPT).trim()
        val content = "[$mood] $excerpt"
        val facets = buildList {
            add("mood:$mood")
            add("kind:chat-mood")
            add("source:$src")
        }
        // Tier=Working — mood is ephemeral by design. SelfOrganizer's
        // episodic-promotion pass eventually rolls recurring mood
        // patterns into longer-lived summaries.
        // Confidence 0.5 — lexical scorer is rough; a real Gemma
        // extraction would land at 0.8+ and replace this row via
        // sha-collision dedup if the content matches.
        runCatching {
            vault.add(
                content = content,
                tier = Tier.Working,
                src = src,
                facets = facets,
                conf = 0.5,
                now = System.currentTimeMillis(),
            )
        }.onFailure { Log.w(TAG, "vault.add failed: ${it.message}") }
        Log.d(TAG, "tracked mood=$mood src=$src on '${excerpt.take(40)}…'")
        return mood
    }

    /**
     * Track variant that incorporates raw-audio acoustic features.
     * Used by voice-originated chat turns where [com.mythara.mic.VoicePcmRecorder]
     * captured PCM alongside the SpeechRecognizer.
     *
     * Runs both the lexical scorer and [AcousticAnalyzer], fuses
     * them via [AcousticMoodFusion], and writes a single vault row
     * with the combined `mood:<cat>` plus the raw acoustic facets
     * (`pitch:high`, `energy:low`, `rate:fast`) so downstream
     * analytics + future per-user calibration can read them
     * independently.
     *
     * Confidence is bumped to 0.7 when the lexical + acoustic
     * signals agree (vs 0.5 for lexical-only), 0.6 when only
     * acoustic produced a label.
     *
     * @param pcm raw Int16 mono samples from VoicePcmRecorder.
     * @param sampleRate the rate AudioRecord ran at (typically 16000).
     */
    suspend fun trackVoice(
        text: String,
        pcm: ShortArray?,
        sampleRate: Int,
    ): String? {
        val lexical = LexicalMoodScorer.score(text)
        if (pcm == null || pcm.isEmpty()) {
            return track(text, fromVoice = true)  // fall back to lexical-only path
        }
        // AcousticAnalyzer wants the transcript's word count to
        // compute speaking rate. Use a cheap whitespace split here
        // — accurate enough for population-level rate buckets.
        val wordCount = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.size
        val features = runCatching {
            acoustic.analyze(
                pcm = pcm,
                validSamples = pcm.size,
                sampleRate = sampleRate,
                wordCount = wordCount,
            )
        }.getOrElse {
            Log.w(TAG, "AcousticAnalyzer.analyze threw: ${it.message}")
            return track(text, fromVoice = true)
        }

        val fused = AcousticMoodFusion.fuse(lexical = lexical, features = features) ?: return null
        val acousticBuckets = acoustic.bucket(features)
        val agree = lexical != null && lexical == fused
        val conf = when {
            agree -> 0.75
            lexical != null -> 0.6   // lexical present but acoustic refined
            else -> 0.6              // acoustic-only
        }

        val excerpt = text.take(MAX_EXCERPT).trim()
        val content = "[$fused] $excerpt"
        val facets = buildList {
            add("mood:$fused")
            add("kind:chat-mood")
            add("source:chat:voice")
            addAll(acousticBuckets) // pitch:/energy:/rate: facets
            if (lexical != null) add("lexical:$lexical")
            add("f0_hz:${features.meanF0Hz.toInt()}")
            add("rms:${"%.3f".format(features.meanRms)}")
            add("wps:${"%.2f".format(features.wordsPerSec)}")
            add("dur_s:${"%.1f".format(features.durationSec)}")
        }
        runCatching {
            vault.add(
                content = content,
                tier = Tier.Working,
                src = "chat:voice",
                facets = facets,
                conf = conf,
                now = System.currentTimeMillis(),
            )
        }.onFailure { Log.w(TAG, "vault.add (voice) failed: ${it.message}") }
        Log.d(TAG, "voice mood=$fused lex=$lexical acoustic=$acousticBuckets f0=${features.meanF0Hz.toInt()}Hz rms=${features.meanRms}")
        return fused
    }

    companion object {
        private const val TAG = "Mythara/Mood"
        private const val MAX_EXCERPT = 160
    }
}
