package com.mythara.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.memory.MemorySettings
import com.mythara.memory.MemorySync
import com.mythara.memory.MemorySyncScheduler
import com.mythara.memory.github.GitHubClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * VM for the Memory Sync settings panel. Holds the editable PAT / owner /
 * repo, surfaces validation results, triggers the WorkManager one-shot
 * for "Sync now".
 */
@HiltViewModel
class MemorySyncViewModel @Inject constructor(
    private val settings: MemorySettings,
    private val sync: MemorySync,
    private val scheduler: MemorySyncScheduler,
) : ViewModel() {

    data class State(
        val pat: String = "",
        val owner: String = MemorySettings.DEFAULT_OWNER,
        val repo: String = MemorySettings.DEFAULT_REPO,
        val enabled: Boolean = false,
        val syncLearnings: Boolean = true,
        val syncSettings: Boolean = true,
        val syncChat: Boolean = false,
        val lastSyncTs: Long = 0,
        val validating: Boolean = false,
        val syncing: Boolean = false,
        val restoring: Boolean = false,
        val restoreConfirmOpen: Boolean = false,
        val validation: Outcome? = null,
        val lastResult: String? = null,
    )

    data class Outcome(val ok: Boolean, val message: String)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val snap = settings.snapshot()
            _state.update {
                it.copy(
                    pat = snap.pat ?: "",
                    owner = snap.owner,
                    repo = snap.repo,
                    enabled = snap.enabled,
                    syncLearnings = snap.syncLearnings,
                    syncSettings = snap.syncSettings,
                    syncChat = snap.syncChat,
                    lastSyncTs = snap.lastSyncTs,
                )
            }
        }
    }

    fun setOwner(v: String) {
        _state.update { it.copy(owner = v, validation = null) }
        viewModelScope.launch { settings.setRepo(owner = v, repo = _state.value.repo) }
    }
    fun setRepo(v: String) {
        _state.update { it.copy(repo = v, validation = null) }
        viewModelScope.launch { settings.setRepo(owner = _state.value.owner, repo = v) }
    }
    fun setEnabled(v: Boolean) {
        _state.update { it.copy(enabled = v) }
        viewModelScope.launch {
            settings.setEnabled(v)
            if (v) scheduler.start() else scheduler.pause()
        }
    }
    fun setScopes(learnings: Boolean, syncSettings: Boolean, chat: Boolean) {
        _state.update { it.copy(syncLearnings = learnings, syncSettings = syncSettings, syncChat = chat) }
        viewModelScope.launch { settings.setScopes(learnings, syncSettings, chat) }
    }

    fun saveAndValidate(pat: String) {
        if (pat.isBlank()) return
        viewModelScope.launch {
            settings.setPat(pat)
            _state.update { it.copy(pat = pat, validating = true, validation = null) }
            val client = GitHubClient(pat)
            val result = when (val v = client.validateToken()) {
                is GitHubClient.Outcome.Ok -> Outcome(true, "Token OK · logged in as ${v.value}")
                is GitHubClient.Outcome.Unauthorized -> Outcome(false, v.message)
                is GitHubClient.Outcome.Error -> Outcome(false, v.message)
                else -> Outcome(false, "Unexpected response")
            }
            _state.update { it.copy(validating = false, validation = result) }
        }
    }

    fun syncNow(force: Boolean = false) {
        _state.update { it.copy(syncing = true, lastResult = null) }
        viewModelScope.launch {
            val report = runCatching { sync.runSync(forcePush = force) }
                .getOrElse { MemorySync.Report(ok = false, message = it.message ?: "error") }
            _state.update {
                it.copy(
                    syncing = false,
                    lastResult = "${if (report.ok) "✓" else "×"} ${report.message}",
                    lastSyncTs = if (report.ok) System.currentTimeMillis() else it.lastSyncTs,
                )
            }
            if (!report.ok) {
                // Also schedule a WorkManager retry so the user gets the sync
                // even if a momentary network glitch caused failure.
                scheduler.fireNow(force = force)
            }
        }
    }

    fun clearToken() {
        viewModelScope.launch {
            settings.clearPat()
            _state.update { it.copy(pat = "", validation = null) }
        }
    }

    fun openRestoreConfirm() = _state.update { it.copy(restoreConfirmOpen = true) }
    fun cancelRestore()       = _state.update { it.copy(restoreConfirmOpen = false) }

    fun confirmRestore() {
        _state.update { it.copy(restoring = true, restoreConfirmOpen = false, lastResult = null) }
        viewModelScope.launch {
            val report = runCatching { sync.runRestore() }
                .getOrElse { MemorySync.RestoreReport(ok = false, message = it.message ?: "restore error") }
            _state.update {
                it.copy(
                    restoring = false,
                    lastResult = "${if (report.ok) "✓ restore" else "× restore"} · ${report.message}",
                )
            }
        }
    }
}
