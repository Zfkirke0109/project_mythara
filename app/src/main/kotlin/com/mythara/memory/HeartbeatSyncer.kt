package com.mythara.memory

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mythara.tasks.TaskExecutor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-backed heartbeat. Triggers memory sync, cross-device task pickup,
 * and presence refresh without keeping Mythara's process alive.
 *
 * One UI aggressively reclaims idle processes, so the old in-process timer was
 * fragile and expensive. WorkManager's 15-minute periodic floor is the platform
 * contract for this deferred sync work; user-initiated actions still call
 * [HeartbeatSyncer.fireNow] for an immediate one-shot.
 */
@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: MemorySyncScheduler,
    private val memorySettings: MemorySettings,
    private val taskExecutor: TaskExecutor,
    private val presenceCache: DevicePresenceCache,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_REASON) ?: "periodic"
        val snap = runCatching { memorySettings.snapshot() }.getOrElse {
            Log.w(TAG, "heartbeat settings read failed: ${it.message}")
            return Result.retry()
        }
        if (!snap.enabled || !snap.configured) {
            Log.v(TAG, "heartbeat($reason): sync disabled/unconfigured - skipping")
            return Result.success()
        }

        Log.d(TAG, "heartbeat($reason): enqueue sync + task tick + presence refresh")
        scheduler.fireNow(force = false)
        runCatching { taskExecutor.tick(maxTasks = 3) }
            .onFailure { Log.w(TAG, "task tick failed: ${it.message}") }
        runCatching { presenceCache.refreshFromHeartbeats() }
            .onFailure { Log.v(TAG, "presence refresh failed: ${it.message}") }
        return Result.success()
    }

    companion object {
        private const val TAG = "Mythara/Heartbeat"
        const val UNIQUE_PERIODIC = "mythara_heartbeat_periodic"
        const val UNIQUE_ONESHOT = "mythara_heartbeat_oneshot"
        const val KEY_REASON = "reason"
    }
}

@Singleton
class HeartbeatSyncer @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val wm: WorkManager get() = WorkManager.getInstance(ctx)

    fun start() {
        val req = PeriodicWorkRequestBuilder<HeartbeatWorker>(PERIOD)
            .setInputData(Data.Builder().putString(HeartbeatWorker.KEY_REASON, "periodic").build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInitialDelay(INITIAL_DELAY)
            .build()
        wm.enqueueUniquePeriodicWork(
            HeartbeatWorker.UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
        Log.d(TAG, "HeartbeatSyncer scheduled via WorkManager (${PERIOD.toMinutes()} min)")
    }

    fun fireNow() {
        val req = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setInputData(Data.Builder().putString(HeartbeatWorker.KEY_REASON, "manual").build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        wm.enqueueUniqueWork(
            HeartbeatWorker.UNIQUE_ONESHOT,
            ExistingWorkPolicy.REPLACE,
            req,
        )
    }

    companion object {
        private const val TAG = "Mythara/Heartbeat"
        val PERIOD: Duration = Duration.ofMinutes(15)
        val INITIAL_DELAY: Duration = Duration.ofMinutes(5)
    }
}
