package com.mythara.resonance

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side fallback HR source for Resonance Mode that reads from
 * **Health Connect** instead of trying to stream the watch's sensor
 * directly.
 *
 * Why: on Samsung Galaxy Watches, third-party access to the HR
 * sensor via `androidx.health.services.client.MeasureClient` is
 * blocked even with all the right permissions and a foreground
 * service holding the registration. `MeasureCallback` accepts the
 * registration, the system service connects, and then no
 * `onAvailabilityChanged` or `onDataReceived` callback ever fires.
 * Samsung's own apps work because they go through Samsung Health,
 * which writes batches of HR readings into Health Connect every
 * ~1 min. Polling Health Connect from the phone is therefore the
 * only reliable HR source available on a Galaxy Watch + Mythara
 * pairing.
 *
 * Trade-off: the resolution drops from the original "1 Hz live
 * stream" to "whatever Samsung Health flushed last." That's ~one
 * batch per minute in steady state, with each batch typically
 * carrying a few samples. The Resonance analyzer already polls every
 * ~30 s and the closed loop adjusts every ~30 s, so this is well
 * within the loop's natural cadence — there's no analyzer-side
 * change required.
 *
 * Lifecycle: started by [ResonanceController.startSession] alongside
 * [ResonanceHrStore.start]; stopped by [endSession] alongside
 * [ResonanceHrStore.stop]. Same scope shape as
 * [ResonanceHrStore.flushOnce] — quiet on errors, never throws.
 */
@Singleton
class ResonanceHcHrPoller @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val hrStore: ResonanceHrStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    /** Highest sample timestamp we've already pushed to [hrStore], so
     *  every poll only forwards genuinely new data. Reset on [start]. */
    @Volatile private var lastPushedTsMs: Long = 0L

    /** Diagnostic counter — number of fresh samples this session, so a
     *  panel / log can confirm Health Connect is alive. */
    @Volatile private var samplesPushed: Int = 0

    /** Begin polling. Idempotent — second call while a poll is active
     *  is a no-op (won't double the load). */
    fun start() {
        if (pollJob?.isActive == true) return
        // Start the bookkeeping clock just BEFORE the session began so
        // the first poll picks up any samples Samsung Health flushed
        // immediately before the user tapped start (covers Samsung's
        // batched-write delay of up to ~1 min).
        lastPushedTsMs = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
        samplesPushed = 0
        pollJob = scope.launch {
            // Kick the first poll immediately so the analyzer's first
            // snapshot can fire with real HR if any is available; then
            // settle into the regular cadence.
            pollOnce()
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                pollOnce()
            }
        }
        Log.d(TAG, "Health Connect HR poller started")
    }

    /** Stop polling. Idempotent. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        Log.d(TAG, "Health Connect HR poller stopped (forwarded $samplesPushed sample(s) this session)")
    }

    private suspend fun pollOnce() {
        runCatching {
            if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) {
                logOnce("Health Connect SDK not available — skipping HR poll")
                return
            }
            val client = HealthConnectClient.getOrCreate(ctx)
            val granted = client.permissionController.getGrantedPermissions()
            if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) {
                logOnce("HR read permission not granted in Health Connect — skipping")
                return
            }

            // Window is "everything since the last sample we pushed,
            // minus a small safety overlap" so a sample arriving
            // out-of-order doesn't get dropped. We dedupe by timestamp
            // below.
            val now = Instant.now()
            val since = Instant.ofEpochMilli(lastPushedTsMs - OVERLAP_MS)
                .coerceAtLeast(now.minusMillis(MAX_LOOKBACK_MS))
            val records = client.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(since, now)),
            ).records

            val flat = records.flatMap { rec ->
                rec.samples.map { s -> s.time.toEpochMilli() to s.beatsPerMinute.toInt() }
            }.sortedBy { it.first }

            // Only push samples newer than the highest one we've seen
            // so far (strict >, not >=, to dedupe on the boundary).
            val fresh = flat.filter { it.first > lastPushedTsMs }
            if (fresh.isEmpty()) return

            for ((tsMs, bpm) in fresh) {
                hrStore.push(bpm, tsMs)
            }
            lastPushedTsMs = fresh.last().first
            samplesPushed += fresh.size
            Log.d(
                TAG,
                "polled HC: pushed ${fresh.size} new HR sample(s), latest ${fresh.last().second} bpm " +
                    "(session total $samplesPushed)",
            )
        }.onFailure {
            logOnce("HC HR poll failed: ${it.message}")
        }
    }

    @Volatile private var lastDiagLogMs: Long = 0L
    private fun logOnce(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastDiagLogMs > 30_000L) {
            Log.d(TAG, msg)
            lastDiagLogMs = now
        }
    }

    companion object {
        private const val TAG = "Mythara/ResonanceHcHr"

        /** Cadence is bounded by Samsung Health's batched-write delay
         *  (~1 min). Polling faster than that just spins the SDK with
         *  zero new data; 10 s is a reasonable balance — fast enough
         *  that a fresh batch lands in the analyzer's next 30 s pass. */
        private const val POLL_INTERVAL_MS = 10_000L

        /** First poll looks ~2 min back so we catch a batch Samsung
         *  Health may have written just before the session opened. */
        private const val INITIAL_LOOKBACK_MS = 120_000L

        /** Per-poll lookback ceiling — never read more than 5 min of
         *  history at once, even if `lastPushedTsMs` is unusually old
         *  (e.g. session paused / device slept). */
        private const val MAX_LOOKBACK_MS = 5L * 60 * 1000

        /** Small overlap on the read window so a sample that arrived
         *  out-of-order doesn't slip through the dedupe. */
        private const val OVERLAP_MS = 5_000L
    }
}
