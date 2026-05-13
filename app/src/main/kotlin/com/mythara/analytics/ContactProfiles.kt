package com.mythara.analytics

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-contact aggregated profile, persisted so the analytics screen
 * can render fast without re-running Gemma every time the user opens
 * it. Re-built periodically (or on demand) by [ContactAnalyticsBuilder]
 * from raw vault entries facetted with `contact:<name>`.
 *
 * The fields divide into:
 *   • Identity — display name + canonical key, phone if known,
 *     favorite + tone status mirrored from FavoritesStore.
 *   • Activity — first / last interaction, total counts.
 *   • Relationship signal — running summary paragraph + free-form
 *     notable traits, both produced by the local Gemma model from
 *     concatenated vault content for that contact.
 *   • Big Five personality — five 0–1 scores estimated by Gemma over
 *     the same content. Updated only when the sample size is large
 *     enough to be meaningful (>= [MIN_BIG_FIVE_SAMPLE] vault rows).
 *
 * "Personality of THIS contact, as Mythara observes them through
 * their messages with the user" — not a clinical assessment, just
 * an LLM-estimated read for response tuning. Surfaced honestly in
 * the UI as "Lumi's read on this person."
 */
@Entity(tableName = "contact_profiles")
data class ContactProfileRow(
    /** Lowercase, trimmed; the canonical lookup key. */
    @PrimaryKey @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val phone: String? = null,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "tone_label") val toneLabel: String? = null,
    @ColumnInfo(name = "first_seen_ms") val firstSeenMs: Long,
    @ColumnInfo(name = "last_interaction_ms") val lastInteractionMs: Long,
    @ColumnInfo(name = "message_count") val messageCount: Int = 0,
    @ColumnInfo(name = "image_count") val imageCount: Int = 0,
    /** JSON array of strings — durable topics ("hiking", "python", "family"). */
    @ColumnInfo(name = "top_topics_json") val topTopicsJson: String = "[]",
    /** Free-form paragraph; how the user relates to / talks with this person. */
    @ColumnInfo(name = "relationship_summary") val relationshipSummary: String? = null,
    /** 0..1 Big Five scores. Null when sample size is too small to estimate. */
    val openness: Double? = null,
    val conscientiousness: Double? = null,
    val extraversion: Double? = null,
    val agreeableness: Double? = null,
    val neuroticism: Double? = null,
    @ColumnInfo(name = "big_five_sample_size") val bigFiveSampleSize: Int = 0,
    @ColumnInfo(name = "big_five_last_updated_ms") val bigFiveLastUpdatedMs: Long? = null,
    /** JSON array of strings — short observed traits beyond Big Five. */
    @ColumnInfo(name = "notable_traits_json") val notableTraitsJson: String = "[]",
    /**
     * JSON array of strings — short, actionable "key points to note"
     * surfaced at the top of the contact detail screen. Things the
     * user would want remembered before their NEXT conversation with
     * this person: recent life events, upcoming dates, sensitive
     * topics, open threads / promises, recurring concerns. Generated
     * by Gemma over the contact's vault content.
     *
     * Distinct from notable_traits which describes WHO the person is;
     * key_points describes WHAT'S HAPPENING in their life right now.
     */
    @ColumnInfo(name = "key_points_json") val keyPointsJson: String = "[]",
    /**
     * Free-form user-authored notes about this contact. Always
     * preserved across rebuilds — Gemma never overwrites this field.
     * Surfaced AT THE TOP of the detail screen and injected
     * prominently into the auto-reply prompt as authoritative
     * context (user-written facts override LLM inferences).
     *
     * Use cases: corrections ("she's allergic to nuts — important"),
     * additional context the chat history can't have learned
     * ("knows her from college, decade-long friendship"),
     * explicit reminders ("don't bring up her brother").
     */
    @ColumnInfo(name = "user_notes") val userNotes: String? = null,
    /** Last time the analytics builder produced / updated this row. */
    @ColumnInfo(name = "last_built_ms") val lastBuiltMs: Long = 0,
) {
    companion object {
        const val MIN_BIG_FIVE_SAMPLE = 6
    }
}

@Dao
interface ContactProfileDao {
    @Query(
        "SELECT * FROM contact_profiles ORDER BY is_favorite DESC, last_interaction_ms DESC",
    )
    fun observeAll(): Flow<List<ContactProfileRow>>

    @Query(
        "SELECT * FROM contact_profiles ORDER BY is_favorite DESC, last_interaction_ms DESC",
    )
    suspend fun listAll(): List<ContactProfileRow>

    @Query("SELECT * FROM contact_profiles WHERE name_key = :key LIMIT 1")
    suspend fun byKey(key: String): ContactProfileRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ContactProfileRow)

    /**
     * Update only the user-authored notes without touching anything
     * else. Avoids racing the analytics builder when the user edits
     * notes mid-rebuild — partial updates beat the full-row replace
     * the upsert path uses.
     */
    @Query("UPDATE contact_profiles SET user_notes = :notes WHERE name_key = :key")
    suspend fun updateUserNotes(key: String, notes: String?)

    @Query("DELETE FROM contact_profiles WHERE name_key = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM contact_profiles")
    suspend fun clear()
}

@Database(entities = [ContactProfileRow::class], version = 3, exportSchema = false)
abstract class ContactProfilesDb : RoomDatabase() {
    abstract fun profiles(): ContactProfileDao
}

@Singleton
class ContactProfileRepository @Inject constructor(@ApplicationContext ctx: Context) {
    private val db: ContactProfilesDb =
        Room.databaseBuilder(ctx, ContactProfilesDb::class.java, "mythara_contact_profiles.db")
            .fallbackToDestructiveMigration()
            .build()
    val dao: ContactProfileDao = db.profiles()
}
