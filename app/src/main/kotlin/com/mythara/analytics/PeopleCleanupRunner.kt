package com.mythara.analytics

import android.util.Log
import com.mythara.memory.HeartbeatSyncer
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bulk "classify every existing ContactProfileRow" pass that
 * fixes the People-pollution problem retroactively: weather
 * notifications ("65 in Naperville"), bank brand names, app
 * accounts that snuck into People when the observer was too
 * permissive, etc.
 *
 * For each row:
 *   1. Run [EntityKindClassifier.classifyExisting] (heuristic-only,
 *      no LLM).
 *   2. Persist `kind` + `kind_confidence` + `kind_classified_at_ms`.
 *   3. Set `is_hidden = true` for anything classified as non-person
 *      so the People list shrinks immediately.
 *
 * Idempotent — re-running on already-classified rows just refreshes
 * the timestamp. On completion fires [HeartbeatSyncer.fireNow] so
 * every paired Mythara device picks up the same kind/hidden state
 * within seconds.
 *
 * Mirrors the [com.mythara.memory.MemoryReorganizerRunner] +
 * [com.mythara.lifeline.RecaptionAllRunner] pattern so the
 * Settings panel reuses the same UI shape (Idle / Running / Done /
 * Failed).
 */
@Singleton
class PeopleCleanupRunner @Inject constructor(
    private val repo: ContactProfileRepository,
    private val classifier: EntityKindClassifier,
    private val heartbeat: Lazy<HeartbeatSyncer>,
) {
    data class Report(
        val totalScanned: Int,
        val personCount: Int,
        val placeCount: Int,
        val orgCount: Int,
        val appCount: Int,
        val notificationCount: Int,
        val unknownCount: Int,
        val hiddenCount: Int,
        val durationMs: Long,
    )

    sealed interface State {
        data object Idle : State
        data class Running(val attempted: Int, val total: Int, val hidden: Int) : State
        data class Done(val report: Report) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    fun start() {
        if (isRunning()) {
            Log.d(TAG, "cleanup already running; ignoring duplicate start")
            return
        }
        val started = System.currentTimeMillis()
        _state.value = State.Running(0, 0, 0)
        job = scope.launch {
            runCatching {
                val rows = runCatching { repo.dao.listAll() }.getOrDefault(emptyList())
                _state.value = State.Running(0, rows.size, 0)
                var person = 0; var place = 0; var org = 0
                var app = 0; var notif = 0; var unknown = 0
                var hidden = 0

                rows.forEachIndexed { index, row ->
                    val verdict = runCatching { classifier.classifyExisting(row) }
                        .getOrElse {
                            Log.w(TAG, "classify failed for ${row.nameKey}: ${it.message}")
                            EntityKindClassifier.Verdict(
                                ContactProfileRow.KIND_UNKNOWN, 0.0f, "exception",
                            )
                        }
                    val shouldHide = verdict.kind != ContactProfileRow.KIND_PERSON
                    runCatching {
                        repo.dao.updateKind(
                            key = row.nameKey,
                            kind = verdict.kind,
                            conf = verdict.confidence,
                            tsMs = System.currentTimeMillis(),
                            // Don't auto-hide rows the user has explicitly
                            // favourited — they've signalled this matters.
                            hidden = shouldHide && !row.isFavorite,
                        )
                    }
                    when (verdict.kind) {
                        ContactProfileRow.KIND_PERSON -> person++
                        ContactProfileRow.KIND_PLACE -> place++
                        ContactProfileRow.KIND_ORG -> org++
                        ContactProfileRow.KIND_APP -> app++
                        ContactProfileRow.KIND_NOTIFICATION -> notif++
                        ContactProfileRow.KIND_UNKNOWN -> unknown++
                    }
                    if (shouldHide && !row.isFavorite) hidden++
                    if (index % 10 == 0 || index == rows.lastIndex) {
                        _state.update { State.Running(index + 1, rows.size, hidden) }
                    }
                }

                val report = Report(
                    totalScanned = rows.size,
                    personCount = person,
                    placeCount = place,
                    orgCount = org,
                    appCount = app,
                    notificationCount = notif,
                    unknownCount = unknown,
                    hiddenCount = hidden,
                    durationMs = System.currentTimeMillis() - started,
                )
                _state.value = State.Done(report)
                Log.d(TAG, "cleanup done: $report")
                // Ship the new kind/hidden state to peers immediately.
                runCatching { heartbeat.get().fireNow() }
                    .onFailure { Log.w(TAG, "post-cleanup sync kick failed: ${it.message}") }
            }.onFailure { e ->
                Log.w(TAG, "cleanup failed: ${e.message}", e)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { current ->
            if (current is State.Running) {
                State.Failed("Cancelled by user at ${current.attempted}/${current.total}")
            } else current
        }
    }

    fun acknowledge() {
        if (!isRunning()) _state.value = State.Idle
    }

    companion object {
        private const val TAG = "Mythara/PeopleCleanup"
    }
}
