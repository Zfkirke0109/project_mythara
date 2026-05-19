package com.mythara.analytics

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-row override for `EntityKindClassifier`. The classifier's
 * heuristics are global — they don't know that "WhatsApp Business"
 * notifications from your favourite tailor are really organisations,
 * or that the "alex" who shows up only in the System UI media-notif
 * package is actually the artist, not the playback controller.
 *
 * This store lets the user explicitly pin a row's `kind` so the next
 * classifier pass — and every subsequent one — returns that kind
 * verbatim with `confidence = 1.0`. Surfaced via long-press on People
 * rows (visible list AND the Hidden sub-screen).
 *
 * Override scope: keyed on `ContactProfileRow.nameKey` (the
 * deterministic per-row identifier already used for cross-device
 * sync). This means an override on Pixel 10 propagates to the Fold +
 * watch via `MemorySync` because the kind ALSO lives on the
 * ContactProfileRow itself — the override store is the source of
 * truth for "user-pinned", but the row already syncs its current
 * kind.
 *
 * NOT synced across devices yet — that's a future improvement that
 * would carry the override-vs-heuristic flag into the wire format.
 * For now: re-set the override on each device if you want it
 * everywhere. Most users only manage People on one device.
 */
@Singleton
class ContactKindOverrideStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    @Serializable
    data class Override(
        val kind: String,
        /** Epoch-ms of when the override was set. Lets the UI show
         *  "pinned 3 days ago" so the user can audit their decisions. */
        val tsMs: Long,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mapSer = MapSerializer(String.serializer(), Override.serializer())

    /** Pin a row to a specific kind. Use one of the
     *  `ContactProfileRow.KIND_*` constants. */
    suspend fun set(nameKey: String, kind: String) {
        val updated = readAll().toMutableMap().apply {
            put(nameKey, Override(kind = kind, tsMs = System.currentTimeMillis()))
        }
        writeAll(updated)
        Log.i(TAG, "pinned $nameKey → $kind")
    }

    /** Look up an existing override. Returns null when the user
     *  hasn't pinned this row, OR when the override is malformed. */
    suspend fun get(nameKey: String): Override? = readAll()[nameKey]

    /** Remove an override so the next classifier pass falls back to
     *  the heuristic cascade. */
    suspend fun clear(nameKey: String) {
        val updated = readAll().toMutableMap().apply { remove(nameKey) }
        writeAll(updated)
        Log.i(TAG, "cleared override for $nameKey")
    }

    /** Whole map — used by the cleanup runner to know which rows
     *  should skip the heuristic pass entirely. */
    suspend fun all(): Map<String, Override> = readAll()

    /** True iff the user has explicitly pinned this row. Convenience
     *  wrapper to keep call sites readable. */
    suspend fun isPinned(nameKey: String): Boolean = get(nameKey) != null

    private suspend fun readAll(): Map<String, Override> {
        val raw = ctx.dataStore.data.first()[KEY_OVERRIDES_JSON] ?: return emptyMap()
        return runCatching { json.decodeFromString(mapSer, raw) }.getOrDefault(emptyMap())
    }

    private suspend fun writeAll(map: Map<String, Override>) {
        val encoded = json.encodeToString(mapSer, map)
        ctx.dataStore.edit { it[KEY_OVERRIDES_JSON] = encoded }
    }

    companion object {
        private const val TAG = "Mythara/KindOverride"
        private val KEY_OVERRIDES_JSON = stringPreferencesKey("kind_overrides_json")
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
            name = "mythara_contact_kind_overrides",
        )
    }
}
