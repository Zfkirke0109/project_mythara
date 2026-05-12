package com.mythara.growth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M8.0 stub for the self-learning vault. Writes timestamped journal
 * entries on each scheduled fire so we can see the cadence working
 * end-to-end before M8.1+ wires Vosk audio capture and the Gemma
 * extractor. Entries land in a DataStore preferences JSON blob —
 * deliberately simple; the real vault (M8.2) is SQLCipher.
 *
 * Self-organization (clustering, promotion, demotion) happens in
 * [SelfOrganizer] once the real vault exists. This stub just appends.
 */
@Singleton
class LearningJournal @Inject constructor(@ApplicationContext private val ctx: Context) {

    @Serializable
    data class Entry(
        val tsMillis: Long,
        val kind: String,            // "nightly" | "weekly" | "manual"
        val note: String,
    )

    private val Context.store by preferencesDataStore(name = "mythara_growth_journal")
    private val keyEntries = stringPreferencesKey("entries.json")

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())

    suspend fun append(entry: Entry) {
        val existing = read()
        val next = (existing + entry).takeLast(MAX_ENTRIES)
        ctx.store.edit { it[keyEntries] = json.encodeToString(serializer, next) }
    }

    suspend fun read(): List<Entry> {
        val raw = ctx.store.data.first()[keyEntries] ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrElse { emptyList() }
    }

    fun observe(): Flow<List<Entry>> = ctx.store.data.map { prefs ->
        prefs[keyEntries]?.let { raw ->
            runCatching { json.decodeFromString(serializer, raw) }.getOrElse { emptyList() }
        } ?: emptyList()
    }

    suspend fun forgetEverything() {
        ctx.store.edit { it.remove(keyEntries) }
    }

    /** Bulk replace — used by the memory-restore flow. Caps at [MAX_ENTRIES]. */
    suspend fun replaceAll(entries: List<Entry>) {
        val capped = entries.sortedBy { it.tsMillis }.takeLast(MAX_ENTRIES)
        ctx.store.edit { it[keyEntries] = json.encodeToString(serializer, capped) }
    }

    companion object {
        /** Soft cap so the stub doesn't grow without bound; the real vault
         *  in M8.2 enforces this via SQLCipher TTL and CapabilityTuner rules. */
        private const val MAX_ENTRIES = 200
    }
}
