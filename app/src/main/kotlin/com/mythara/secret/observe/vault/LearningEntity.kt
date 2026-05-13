package com.mythara.secret.observe.vault

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One durable record in the learning vault. Same conceptual shape as
 * the agentmemory-style [com.mythara.memory.MemoryRecord] that syncs to
 * GitHub — short keys live on the wire, longer descriptive names live
 * in code. The two flow into each other via [LearningRecord.toMemoryRecord].
 *
 * Fields:
 *  - id           ULID-style time-sortable identifier
 *  - tsMillis     epoch millis
 *  - tier         "w" working | "e" episodic | "s" semantic | "p" procedural
 *  - src          provenance ("observe" / "chat" / "growth:..." / "extract:heuristic")
 *  - content      durable text payload (already secret-scrubbed by writer)
 *  - sha          SHA-256(content) 24-char prefix; dedup key
 *  - conf         confidence 0..1
 *  - facets       JSON-encoded List<String> of dimension:value tags
 *  - embedding    100-dim USE-Lite vector as little-endian float32 bytes,
 *                 or null if model wasn't available at write time
 *  - embModel     identifier of the embedder that produced [embedding]
 *  - ref          optional back-link to source event (transcript file, msg id, …)
 *  - seen         reinforcement counter; bumped when same sha observed again
 *  - lastSeenMs   most-recent reinforcement timestamp
 *  - synced       true once this record has been pushed to the GitHub repo
 *  - syncedAtMs   timestamp of the last successful sync, for diagnostics
 *
 * The `Index(sha, unique=true)` is the structural enforcement of the
 * dedup invariant — duplicate observations bump [seen] on the canonical
 * row instead of multiplying rows.
 */
@Entity(
    tableName = "learnings",
    indices = [
        Index(value = ["sha"], unique = true),
        Index(value = ["tier"]),
        Index(value = ["tsMillis"]),
        Index(value = ["synced"]),
    ],
)
data class LearningEntity(
    @PrimaryKey val id: String,
    val tsMillis: Long,
    val tier: String,
    val src: String,
    val content: String,
    val sha: String,
    val conf: Double = 1.0,
    /** JSON array of strings: `["topic:python","kind:preference"]`. */
    val facets: String = "[]",
    val embedding: ByteArray? = null,
    @ColumnInfo(name = "emb_model") val embModel: String? = null,
    val ref: String? = null,
    val seen: Int = 1,
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long,
    val synced: Boolean = false,
    @ColumnInfo(name = "synced_at_ms") val syncedAtMs: Long? = null,
) {
    // Room needs explicit equals/hashCode for entities with array fields
    // — the auto-generated equals would compare ByteArray identity, not
    // content, and Room's bookkeeping breaks on that.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LearningEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
