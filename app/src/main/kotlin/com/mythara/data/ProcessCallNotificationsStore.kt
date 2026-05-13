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
 * Toggle for whether the agent reacts to phone / VoIP call
 * notifications.
 *
 * Default OFF. By default, anything Mythara classifies as a call
 * notification (Notification.category == CATEGORY_CALL, known dialer
 * packages, body patterns like "incoming call" / "missed call") is
 * dropped before reaching any agent path — no triage, no auto-reply,
 * no [notif] auto-process. Calls are a fundamentally different
 * interaction mode from text and the agent has nothing useful to do
 * with them by default.
 *
 * When ON: call notifications flow through the normal pipeline. The
 * agent can decide what to do with them — surface a quick "X is
 * calling" via TTS, log them, etc. Useful for power users who want
 * the agent narrating call activity.
 */
@Singleton
class ProcessCallNotificationsStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_process_call_notifications")

    private val keyEnabled = booleanPreferencesKey("process_call_notifications.enabled")

    fun enabledFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyEnabled] ?: DEFAULT_ENABLED }

    suspend fun isEnabled(): Boolean = enabledFlow().first()

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }

    companion object {
        /** Default OFF — calls are user-explicit, agent doesn't touch them. */
        const val DEFAULT_ENABLED = false
    }
}
