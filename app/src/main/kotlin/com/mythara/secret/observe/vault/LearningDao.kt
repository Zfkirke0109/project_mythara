package com.mythara.secret.observe.vault

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LearningDao {
    @Query("SELECT * FROM learnings ORDER BY tsMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LearningEntity>>

    @Query("SELECT * FROM learnings ORDER BY tsMillis DESC LIMIT :limit OFFSET :offset")
    suspend fun listRecent(limit: Int = 50, offset: Int = 0): List<LearningEntity>

    @Query("SELECT * FROM learnings WHERE tier = :tier ORDER BY tsMillis DESC LIMIT :limit")
    suspend fun listByTier(tier: String, limit: Int = 100): List<LearningEntity>

    @Query("SELECT * FROM learnings WHERE synced = 0 ORDER BY tsMillis ASC LIMIT :limit")
    suspend fun listUnsynced(limit: Int = 500): List<LearningEntity>

    @Query("SELECT * FROM learnings WHERE sha = :sha LIMIT 1")
    suspend fun findBySha(sha: String): LearningEntity?

    @Query("SELECT COUNT(*) FROM learnings")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM learnings WHERE tier = :tier")
    suspend fun countByTier(tier: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringConflict(entity: LearningEntity): Long

    @Update
    suspend fun update(entity: LearningEntity)

    @Query("UPDATE learnings SET synced = 1, synced_at_ms = :tsMillis WHERE id = :id")
    suspend fun markSynced(id: String, tsMillis: Long)

    @Query("UPDATE learnings SET seen = seen + 1, last_seen_ms = :tsMillis WHERE sha = :sha")
    suspend fun reinforce(sha: String, tsMillis: Long)

    @Query("DELETE FROM learnings")
    suspend fun clear()

    /**
     * Atomic insert-or-reinforce: if a record with the same SHA already
     * exists we just bump its [LearningEntity.seen] counter; otherwise the
     * new record is inserted.
     *
     * Returns true if a new row was inserted, false if it was a reinforcement.
     */
    @Transaction
    suspend fun upsert(entity: LearningEntity): Boolean {
        val rowId = insertIgnoringConflict(entity)
        return if (rowId == -1L) {
            reinforce(entity.sha, entity.tsMillis)
            false
        } else {
            true
        }
    }
}
