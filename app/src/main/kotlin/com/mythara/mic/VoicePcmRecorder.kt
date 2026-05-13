package com.mythara.mic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Captures raw 16-bit mono PCM @ 16 kHz alongside a chat-side voice
 * input flow, so [com.mythara.secret.observe.acoustic.AcousticAnalyzer]
 * can extract pitch / energy / speaking-rate features from the
 * same utterance the user said.
 *
 * The chat voice paths use Android's [android.speech.SpeechRecognizer]
 * (on-device on API 31+), which buffers the audio internally and
 * doesn't expose it. Running our own [AudioRecord] in parallel against
 * the VOICE_RECOGNITION source is the workaround — on Pixel devices
 * concurrent capture in the same UID is allowed (multiple AudioRecord
 * clients can coexist when the OS routes them through the same audio
 * session). When the OEM refuses (some non-Pixel devices), [start]
 * returns false and the caller proceeds with text-only mood scoring.
 *
 * Lifecycle is start/stop only — no flow events. Caller is expected
 * to scope start() and stop() around the SpeechRecognition.listen
 * collection so the audio window aligns with the recognised utterance.
 */
class VoicePcmRecorder(
    private val ctx: Context,
    private val sampleRate: Int = SAMPLE_RATE,
) {

    /**
     * Result of one capture session. `pcm` is the contiguous Int16
     * samples concatenated from every read; `validSamples` is the
     * actual count (always == pcm.size in v1 since we don't
     * pre-allocate). `sampleRate` matches what AudioRecord ran at.
     */
    data class Captured(val pcm: ShortArray, val sampleRate: Int, val durationMs: Long)

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureJob: Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var buffer: ArrayList<ShortArray>? = null
    @Volatile private var startedAtMs: Long = 0L

    /**
     * Start recording. Returns true if AudioRecord initialised and is
     * actively reading; false if the mic permission isn't granted,
     * the buffer min-size lookup failed, or the OS refused
     * concurrent capture. Idempotent — calling twice without
     * [stop] in between leaves the existing recorder running.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recorder != null) return true
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "RECORD_AUDIO not granted; skipping acoustic capture")
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize failed: $minBuf")
            return false
        }
        val bufBytes = minBuf * 4 // 4x headroom for slow reads
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
            )
        }.getOrElse {
            Log.w(TAG, "AudioRecord ctor threw", it)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord state=${rec.state}; OS refused concurrent capture")
            runCatching { rec.release() }
            return false
        }
        rec.startRecording()
        recorder = rec
        buffer = ArrayList(64)
        startedAtMs = System.currentTimeMillis()
        captureJob = scope.launch {
            val chunk = ShortArray(CHUNK_SAMPLES)
            while (coroutineContext[Job]?.isActive != false) {
                val read = runCatching { rec.read(chunk, 0, chunk.size) }.getOrElse {
                    Log.w(TAG, "AudioRecord.read threw", it)
                    break
                }
                if (read <= 0) {
                    // ERROR_INVALID_OPERATION (-3) or ERROR_BAD_VALUE (-2)
                    // — break out and let stop() clean up.
                    if (read != 0) Log.w(TAG, "AudioRecord.read returned $read")
                    break
                }
                buffer?.add(chunk.copyOf(read))
            }
        }
        Log.d(TAG, "AudioRecord capture started @${sampleRate}Hz buf=${bufBytes}B")
        return true
    }

    /**
     * Stop recording and return the concatenated PCM buffer. Returns
     * null if [start] never succeeded or no samples were captured.
     * Safe to call multiple times — subsequent calls return null.
     */
    fun stop(): Captured? {
        val rec = recorder ?: return null
        recorder = null
        val job = captureJob
        captureJob = null
        runCatching {
            rec.stop()
            rec.release()
        }
        // Wait for the capture coroutine to wind down so we don't
        // race the `buffer.add` calls. Should complete in <50ms.
        runCatching { runBlocking { job?.cancelAndJoin() } }

        val chunks = buffer ?: return null
        buffer = null
        if (chunks.isEmpty()) return null
        val total = chunks.sumOf { it.size }
        val merged = ShortArray(total)
        var i = 0
        for (c in chunks) {
            c.copyInto(merged, destinationOffset = i, startIndex = 0, endIndex = c.size)
            i += c.size
        }
        val duration = System.currentTimeMillis() - startedAtMs
        Log.d(TAG, "AudioRecord capture stopped: ${total} samples (~${total / sampleRate}s)")
        return Captured(pcm = merged, sampleRate = sampleRate, durationMs = duration)
    }

    /** Best-effort teardown — called from coroutine cancellation paths. */
    fun release() {
        runCatching { stop() }
        runCatching { scope.cancel("released") }
    }

    companion object {
        private const val TAG = "Mythara/VoicePcm"
        /** 16 kHz Int16 mono — same as Observe's AudioRecorder + Vosk pipeline. */
        const val SAMPLE_RATE = 16_000
        /** ~62.5ms per read = ~16 reads/sec. Smooth coverage without busy-looping. */
        private const val CHUNK_SAMPLES = 1024
    }
}
