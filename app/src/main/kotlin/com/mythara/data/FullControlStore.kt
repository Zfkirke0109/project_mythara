package com.mythara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Full Control** — the user's top-level "no friction" override.
 * When enabled, [com.mythara.agent.CriticalActionGuard] short-
 * circuits its policy checks and returns `Decision.Allow` for
 * every side-effect tool call, regardless of what's in the
 * [RestrictedAppsStore] block / critical lists. The Confirmation
 * popup that normally fires for critical-list apps (Uber, Amazon,
 * DoorDash, etc.) will not appear.
 *
 * Off by default. Opt-in only — flipped from the Restricted Apps
 * panel in Settings. The trade-off is explicit: Full Control means
 * the agent can fire side-effect tools (place orders, send
 * messages, drive UI) inside any app with no confirmation, INCLUDING
 * apps the user previously added to the blocked-banking list. The
 * user is choosing to be the sole gatekeeper.
 *
 * Same SharedPreferences-via-DataStore pattern as the rest of the
 * app's per-feature toggles.
 */
@Singleton
class FullControlStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_full_control")

    private val keyEnabled = booleanPreferencesKey("enabled")

    /** Reactive view — a toggle flip is observed by every consumer
     *  immediately, no app restart required. */
    fun enabledFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyEnabled] ?: false }

    /** Synchronous read — the guard's `evaluate()` is suspending so
     *  this is fine to await on the IO dispatcher per call. The
     *  underlying DataStore caches the latest value in memory after
     *  the first read so successive `first()`s are essentially free. */
    suspend fun isEnabled(): Boolean = enabledFlow().first()

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }
}
