package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.lifeline.LifelineEntity
import com.mythara.lifeline.LifelineRepository
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningEntity
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `search_memory` — query Mythara's [LearningVault] for entries the
 * agent has accumulated about the user, contacts, mood, traits,
 * notes, places, etc.
 *
 * The vault is Mythara's "long-term memory": every chat turn, every
 * observation from Observe mode, every persona-trait extraction
 * lands here as a faceted record. This tool lets the agent
 * introspect that memory at conversation time — e.g.
 *
 *   • "what do you remember about my running habit?"
 *       → search_memory(query='running')
 *   • "what have I told you about Sarah?"
 *       → search_memory(contact='sarah')
 *   • "summarize my mood patterns this week"
 *       → search_memory(kind='chat-mood', since_days=7)
 *   • "what notes have I asked you to remember?"
 *       → search_memory(kind='explicit-note')
 *
 * ## Match semantics
 *
 * Filters are combined with AND. Each filter is optional:
 *
 *   • `query` — case-insensitive substring match against the row's
 *     `content` field.
 *   • `contact` — matches rows facetted with `contact:<nameKey>` or
 *     `target:contact:<nameKey>` (both forms used elsewhere in the
 *     codebase). Pass the nameKey (lowercase). Both Sarah and SARAH
 *     are case-folded to "sarah".
 *   • `kind` — facet-prefix match against `kind:<value>`. Common
 *     kinds: `chat-mood`, `chat-persona`, `trait`, `explicit-note`,
 *     `persona`, `self-profile`, `health-snapshot`, `behavior-event`.
 *   • `dim` — for `kind:trait` rows only; matches facet `dim:<value>`.
 *     Values: `big5`, `values`, `preference`, `concern`, `comm-style`.
 *   • `since_days` / `since_ms` — only rows newer than this. Default:
 *     no time filter (whole vault).
 *
 * Returns up to `limit` matches (default 12, max 50) as a JSON array
 * of `{ts_ms, tier, src, conf, seen, content, facets}` entries,
 * ordered newest-first.
 */
@Singleton
class SearchMemoryTool @Inject constructor(
    private val vault: LearningVault,
    private val lifelineRepo: LifelineRepository,
) : Tool {
    override val name = "search_memory"
    override val description =
        "Query Mythara's long-term memory: the LearningVault (chat / mood / persona / notes / " +
            "behaviour / observe transcripts) AND the Lifeline photo archive (captions / detected " +
            "people / place / time). Combine query / contact / kind / dim / since_* with AND. " +
            "Photos are included by default; set include_photos=false to limit to text rows only. " +
            "Newest-first across both surfaces."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "Case-insensitive substring match against row content.")
            })
            put("contact", buildJsonObject {
                put("type", "string")
                put("description", "Filter to rows facetted for this contact (nameKey, lowercase). e.g. 'sarah', 'mom'.")
            })
            put("kind", buildJsonObject {
                put("type", "string")
                put("description", "Filter to facet `kind:<value>`. Common: chat-mood, chat-persona, trait, " +
                    "explicit-note, persona, self-profile, behavior-event.")
            })
            put("dim", buildJsonObject {
                put("type", "string")
                put("description", "For kind=trait only: filter to facet `dim:<value>`. Values: big5, values, " +
                    "preference, concern, comm-style.")
            })
            put("since_days", buildJsonObject {
                put("type", "integer")
                put("description", "Only rows newer than N days ago. Mutually exclusive with since_ms.")
            })
            put("since_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Only rows with ts_ms >= this epoch-ms. Mutually exclusive with since_days.")
            })
            put("limit", buildJsonObject {
                put("type", "integer")
                put("description", "Max rows to return. Default 12, max 50.")
            })
            put("include_photos", buildJsonObject {
                put("type", "boolean")
                put("description", "Include Lifeline photo captions / metadata in the result set. " +
                    "Default true. When true, matching photos appear as " +
                    "`{kind:\"photo\", lifeline_id, ts_ms, caption, place, detected_contacts, mime, " +
                    "uri, source_device}` entries alongside vault rows.")
            })
        })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull()?.trim()?.lowercase().orEmpty()
        val contact = args["contact"]?.jsonPrimitive?.contentOrNull()?.trim()?.lowercase().orEmpty()
        val kind = args["kind"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val dim = args["dim"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val sinceDays = args["since_days"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull()
        val sinceMsRaw = args["since_ms"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull()
        val limit = (args["limit"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 12)
            .coerceIn(1, 50)
        val includePhotos = args["include_photos"]?.jsonPrimitive?.contentOrNull()
            ?.toBooleanStrictOrNull() ?: true

        val sinceMs = when {
            sinceMsRaw != null -> sinceMsRaw
            sinceDays != null -> System.currentTimeMillis() - TimeUnit.DAYS.toMillis(sinceDays)
            else -> 0L
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                // The vault has no faceted SQL query layer, so we
                // pull recent rows + filter in-memory. We pull from
                // all tiers separately to keep the per-tier limit
                // reasonable. 800 across Working + Semantic + Episodic
                // covers ~the last day of observation traffic.
                val all = mutableListOf<LearningEntity>().apply {
                    runCatching { addAll(vault.listByTier(Tier.Working, limit = 400)) }
                    runCatching { addAll(vault.listByTier(Tier.Semantic, limit = 400)) }
                    runCatching { addAll(vault.listByTier(Tier.Episodic, limit = 200)) }
                    runCatching { addAll(vault.listByTier(Tier.Procedural, limit = 50)) }
                }
                val filtered = all.asSequence()
                    .filter { row ->
                        val facets = vault.decodeFacets(row)
                        // query: substring against content
                        if (query.isNotBlank() && !row.content.lowercase().contains(query)) {
                            return@filter false
                        }
                        // kind: facet exact match
                        if (kind.isNotBlank() && "kind:$kind" !in facets) return@filter false
                        // dim: facet exact match
                        if (dim.isNotBlank() && "dim:$dim" !in facets) return@filter false
                        // contact: either `contact:<key>` or `target:contact:<key>`
                        if (contact.isNotBlank()) {
                            val hit = facets.any { f ->
                                f == "contact:$contact" || f == "target:contact:$contact"
                            }
                            if (!hit) return@filter false
                        }
                        if (sinceMs > 0L && row.tsMillis < sinceMs) return@filter false
                        true
                    }
                    .distinctBy { it.sha }
                    .sortedByDescending { it.tsMillis }
                    .take(limit)
                    .toList()

                // Photo matches — captioned Lifeline rows that match
                // the same query / contact / since filters. Kind / dim
                // are vault-only concepts so they bypass the photo
                // search (an explicit kind:trait, for example, won't
                // produce photo hits).
                val photoMatches: List<LifelineEntity> = if (includePhotos && kind.isBlank() && dim.isBlank()) {
                    runCatching { lifelineRepo.dao.listAllLocal() }.getOrDefault(emptyList())
                        .asSequence()
                        .filter { row -> matchesPhoto(row, query, contact, sinceMs) }
                        .sortedByDescending { it.takenMs }
                        .take(limit)
                        .toList()
                } else emptyList()

                val out = StringBuilder("""{"matches":[""")
                var first = true
                filtered.forEach { row ->
                    if (!first) out.append(',')
                    first = false
                    val facets = vault.decodeFacets(row)
                    out.append('{')
                    out.append("\"kind\":\"memory\",")
                    out.append("\"ts_ms\":${row.tsMillis},")
                    out.append("\"tier\":\"${row.tier}\",")
                    out.append("\"src\":\"${row.src.escape()}\",")
                    out.append("\"conf\":${row.conf},")
                    out.append("\"seen\":${row.seen},")
                    out.append("\"content\":${jsonString(row.content)},")
                    out.append("\"facets\":[${facets.joinToString(",") { "\"${it.escape()}\"" }}]")
                    out.append('}')
                }
                photoMatches.forEach { row ->
                    if (!first) out.append(',')
                    first = false
                    out.append('{')
                    out.append("\"kind\":\"photo\",")
                    out.append("\"lifeline_id\":${row.id},")
                    out.append("\"ts_ms\":${row.takenMs},")
                    out.append("\"caption\":${jsonString(row.captionText.orEmpty())},")
                    out.append("\"caption_status\":\"${row.captionStatus.escape()}\",")
                    out.append("\"caption_model\":\"${row.captionModel.orEmpty().escape()}\",")
                    out.append("\"user_context\":${jsonString(row.userContext.orEmpty())},")
                    out.append("\"place\":${jsonString(row.placeLabel.orEmpty())},")
                    if (row.lat != null && row.lng != null) {
                        out.append("\"lat\":${row.lat},\"lng\":${row.lng},")
                    }
                    out.append("\"detected_contacts\":${jsonString(row.detectedContactsJson.orEmpty())},")
                    out.append("\"mime\":\"${row.mimeType.escape()}\",")
                    out.append("\"uri\":\"${row.uri.escape()}\",")
                    out.append("\"source_device\":\"${(row.sourceDeviceType ?: "phone").escape()}\"")
                    out.append('}')
                }
                out.append("],\"count\":${filtered.size + photoMatches.size},")
                out.append("\"memory_count\":${filtered.size},")
                out.append("\"photo_count\":${photoMatches.size},")
                out.append("\"scanned\":${all.size}}")
                ToolResult.ok(out.toString())
            }.getOrElse {
                ToolResult.fail("search_memory_failed: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    /** Matching rules for a Lifeline row:
     *   - query  → case-insensitive substring across caption_text,
     *               user_context, place_label, detected_contacts.
     *   - contact → matches if the contact's nameKey appears in the
     *               detected_contacts JSON blob.
     *   - sinceMs → taken_ms >= sinceMs. */
    private fun matchesPhoto(
        row: LifelineEntity,
        query: String,
        contact: String,
        sinceMs: Long,
    ): Boolean {
        if (sinceMs > 0L && row.takenMs < sinceMs) return false
        if (query.isNotBlank()) {
            val haystack = buildString {
                append(row.captionText.orEmpty()); append(' ')
                append(row.userContext.orEmpty()); append(' ')
                append(row.placeLabel.orEmpty()); append(' ')
                append(row.detectedContactsJson.orEmpty()); append(' ')
                append(row.displayName)
            }.lowercase()
            if (!haystack.contains(query)) return false
        }
        if (contact.isNotBlank()) {
            val det = row.detectedContactsJson.orEmpty().lowercase()
            if (!det.contains("\"$contact\"") && !det.contains(contact)) return false
        }
        // Skip rows that have no caption AND no user context — they
        // carry no searchable signal and would clutter the result.
        if (row.captionText.isNullOrBlank() && row.userContext.isNullOrBlank()) return false
        return true
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
