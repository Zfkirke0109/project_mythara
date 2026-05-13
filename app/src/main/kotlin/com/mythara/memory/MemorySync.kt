package com.mythara.memory

import android.util.Log
import com.mythara.data.HistoryRepository
import com.mythara.data.MessageRow
import com.mythara.data.SettingsStore
import com.mythara.growth.LearningJournal
import com.mythara.memory.github.GitHubClient
import com.mythara.memory.github.GitHubClient.Outcome
import com.mythara.minimax.Region
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes Mythara's durable state to a user-owned GitHub repo. Single
 * entry point per direction: [runSync] pushes; [runRestore] pulls.
 *
 * **Repo layout** (mobile-optimised, agentmemory-style tiers — see
 * github.com/rohitg00/agentmemory for the architectural pattern):
 *
 * ```
 * mythara_memory/
 *   README.md
 *   MEMORY.md                       — bridge file: top-K active records (Markdown)
 *   manifest.json                   — version + per-file SHA cache + lastSync
 *   working/<YYYY-MM-DD>.jsonl      — raw observations, one per line
 *   episodic/<YYYY-W##>.jsonl       — weekly session summaries  (M8.3+)
 *   semantic/facts.jsonl            — durable facts / preferences (M8.3+)
 *   procedural/workflows.jsonl      — action patterns           (M8.4+)
 *   settings/preferences.json       — region + model + non-secret prefs
 *   conversations/<YYYY-MM-DD>.jsonl — opt-in chat history per local day
 * ```
 *
 * Each memory record uses short keys (`t`, `src`, `conf`, `sha`, `ref`,
 * `seen`) for byte-efficient JSONL — see [MemoryRecord]. Compaction +
 * cross-tier promotion are M8.3+ work; today only the [Tier.Working]
 * tier is populated, with per-day partitioning. The tier directories
 * for episodic/semantic/procedural are written as empty placeholders
 * so they show up in `git ls-tree` and the layout reads correctly.
 *
 * **Privacy invariants** (enforced by code):
 *  - MiniMax API key, GitHub PAT, Tink keyset, Secret-mode password,
 *    Observe raw audio/transcripts — never written to this repo.
 *  - All record content runs through [SecretScrubber] before write.
 *  - The repo MUST be private. We don't enforce this — but the README
 *    and the create-repo flow set `private = true`.
 */
@Singleton
class MemorySync @Inject constructor(
    private val memorySettings: MemorySettings,
    private val journal: LearningJournal,
    private val appSettings: SettingsStore,
    private val history: HistoryRepository,
    private val vault: LearningVault,
) {
    data class Report(
        val ok: Boolean,
        val message: String,
        val filesWritten: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
    )

    data class RestoreReport(
        val ok: Boolean,
        val message: String,
        val learningsRestored: Int = 0,
        val chatRowsRestored: Int = 0,
        val settingsRestored: Boolean = false,
        val filesRead: List<String> = emptyList(),
    )

    private val tag = "Mythara/Memory"

    /** Compact (no pretty-print) — bytes matter. Tolerant of unknown keys for forward compat. */
    private val json = Json {
        encodeDefaults = false
        prettyPrint = false
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** For the human-facing manifest only — pretty-printed. */
    private val manifestJson = Json {
        encodeDefaults = false
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun runSync(forcePush: Boolean = false): Report {
        val cfg = memorySettings.snapshot()
        if (!cfg.enabled) return Report(ok = false, message = "Memory sync disabled.")
        if (!cfg.configured) return Report(ok = false, message = "Set a GitHub token + repo in Settings.")

        val client = GitHubClient(cfg.pat!!)
        when (val v = client.validateToken()) {
            is Outcome.Ok -> Log.d(tag, "PAT ok for ${v.value}")
            is Outcome.Unauthorized -> return Report(ok = false, message = "GitHub token rejected.")
            else -> return Report(ok = false, message = "GitHub auth check failed.")
        }

        when (val r = client.ensureRepo(cfg.owner, cfg.repo, createIfMissing = true)) {
            is Outcome.Ok -> Log.d(tag, "repo ${r.value.fullName} ready (private=${r.value.private})")
            is Outcome.Unauthorized -> return Report(ok = false, message = "Token lacks `repo` scope.")
            is Outcome.Conflict -> return Report(ok = false, message = "Repo create rejected: ${r.message}")
            is Outcome.NotFound -> return Report(ok = false, message = "Repo missing and could not be created.")
            is Outcome.Error -> return Report(ok = false, message = "GitHub: ${r.message}")
        }

        val manifest: ManifestV2 = cfg.manifestJson?.let {
            runCatching { manifestJson.decodeFromString(ManifestV2.serializer(), it) }.getOrNull()
        } ?: ManifestV2()

        val now = System.currentTimeMillis()
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        // README + MEMORY.md (always; they're cheap and the format docs need to be visible).
        putWithCache(client, cfg, README_PATH, README_BODY, manifest,
            "mythara: seed README", written, skipped)

        // ---- working/<day>.jsonl  — current tier of raw learnings
        if (cfg.syncLearnings) {
            val entries = journal.read()
            val recordsByDay = entries.groupBy { isoLocalDate(it.tsMillis) }
            for ((day, dayEntries) in recordsByDay) {
                val body = dayEntries.joinToString("\n") { entry ->
                    val rec = entryToWorkingRecord(entry)
                    json.encodeToString(MemoryRecord.serializer(), rec)
                }
                val path = "${Tier.Working.dir}/$day.jsonl"
                putWithCache(client, cfg, path, body, manifest,
                    "mythara: working/$day (+${dayEntries.size})", written, skipped)
            }

            // Update MEMORY.md bridge — top recent working records, human-readable.
            val memoryMd = renderMemoryBridge(entries, cfg)
            putWithCache(client, cfg, MEMORY_PATH, memoryMd, manifest,
                "mythara: bridge MEMORY.md", written, skipped)
        }

        // ---- semantic/<topic>.jsonl — durable extracted facts from the vault.
        //      We sync only `tier=semantic` records, never raw transcripts
        //      (working tier stays local-only per the privacy contract).
        //      Records are grouped by their first `topic:*` facet so the
        //      repo's semantic/ directory reads like a Karpathy-style wiki.
        val unsynced = vault.unsyncedRecords()
        if (unsynced.isNotEmpty()) {
            val semanticOnly = unsynced.filter { it.tier == Tier.Semantic.code }
            val byTopic: Map<String, List<com.mythara.secret.observe.vault.LearningEntity>> =
                semanticOnly.groupBy { entity ->
                    val facets = vault.decodeFacets(entity)
                    facets.firstOrNull { it.startsWith("topic:") }?.removePrefix("topic:")?.ifBlank { "misc" } ?: "misc"
                }
            for ((topic, records) in byTopic) {
                val body = records.joinToString("\n") { entity ->
                    json.encodeToString(MemoryRecord.serializer(), vault.toMemoryRecord(entity))
                }
                val path = "${Tier.Semantic.dir}/$topic.jsonl"
                putWithCache(client, cfg, path, body, manifest,
                    "mythara: semantic/$topic (+${records.size})", written, skipped)
                // Mark these as synced so subsequent runs don't re-push them
                // until/unless they're updated.
                val syncTs = System.currentTimeMillis()
                for (r in records) vault.markSynced(r.id, syncTs)
            }
            // working-tier records (raw transcripts) are deliberately NOT
            // synced; leave them as unsynced=true forever in the local vault.
        }

        // ---- tier placeholders for episodic/procedural. semantic/ has
        //      real content above (or will once Observe runs); we still
        //      seed it with .gitkeep on the first sync if no records yet.
        for (tier in TIER_PLACEHOLDERS) {
            val path = "${tier.dir}/.gitkeep"
            if (!manifest.files.containsKey(path)) {
                putWithCache(client, cfg, path, PLACEHOLDER_BODY, manifest,
                    "mythara: seed ${tier.dir}/", written, skipped)
            }
        }

        // ---- settings/preferences.json (non-secret prefs only)
        if (cfg.syncSettings) {
            val snap = appSettings.snapshot()
            val obj = SettingsExport(region = snap.region.name, model = snap.model)
            val body = manifestJson.encodeToString(SettingsExport.serializer(), obj)
            putWithCache(client, cfg, "settings/preferences.json", body, manifest,
                "mythara: sync settings", written, skipped)
        }

        // ---- conversations per-day (opt-in)
        if (cfg.syncChat) {
            val rows = history.dao.listAll()
            val byDay = rows.groupBy { isoLocalDate(it.tsMillis) }
            for ((day, dayRows) in byDay) {
                val body = dayRows.joinToString("\n") {
                    json.encodeToString(ChatRowExport.serializer(), ChatRowExport(
                        t = it.tsMillis,
                        role = it.role,
                        content = SecretScrubber.scrub(it.content.orEmpty()).ifBlank { null },
                        toolCallsJson = it.toolCallsJson,
                        toolCallId = it.toolCallId,
                        name = it.name,
                    ))
                }
                val path = "conversations/$day.jsonl"
                putWithCache(client, cfg, path, body, manifest,
                    "mythara: chat $day", written, skipped)
            }
        }

        // ---- manifest.json (always last; lastSyncTs always changes)
        manifest.lastSyncTsMillis = now
        manifest.version = ManifestV2.CURRENT_VERSION
        val manifestBody = manifestJson.encodeToString(ManifestV2.serializer(), manifest)
        putWithCache(client, cfg, "manifest.json", manifestBody, manifest,
            "mythara: manifest @ ${isoUtc(now)}", written, skipped, isManifestItself = true)

        memorySettings.setLastSyncTs(now)
        memorySettings.setManifestJson(manifestJson.encodeToString(ManifestV2.serializer(), manifest))

        return Report(
            ok = true,
            message = "Synced ${written.size} file(s) to ${cfg.owner}/${cfg.repo}.",
            filesWritten = written,
            skipped = skipped,
        )
    }

    /** Pull from repo + materialise into local stores. REPLACE semantics. */
    suspend fun runRestore(): RestoreReport {
        val cfg = memorySettings.snapshot()
        if (!cfg.configured) return RestoreReport(ok = false, message = "Set a GitHub token + repo in Settings first.")

        val client = GitHubClient(cfg.pat!!)
        when (val v = client.validateToken()) {
            is Outcome.Ok -> Log.d(tag, "PAT ok for ${v.value} (restore)")
            is Outcome.Unauthorized -> return RestoreReport(ok = false, message = "GitHub token rejected.")
            else -> return RestoreReport(ok = false, message = "GitHub auth check failed.")
        }

        val manifestRead = client.readFile(cfg.owner, cfg.repo, "manifest.json")
        if (manifestRead is Outcome.NotFound) {
            return RestoreReport(ok = false, message = "Repo has no manifest — nothing to restore yet.")
        }
        if (manifestRead !is Outcome.Ok) {
            return RestoreReport(ok = false, message = "Could not read manifest from repo.")
        }
        val manifest = runCatching {
            manifestJson.decodeFromString(ManifestV2.serializer(), manifestRead.value.text)
        }.getOrElse { return RestoreReport(ok = false, message = "Manifest is malformed.") }

        var learnings = 0
        var chatRows = 0
        var settingsOk = false
        val filesRead = mutableListOf("manifest.json")

        // working/<day>.jsonl files in manifest → LearningJournal.replaceAll
        val workingRecords = mutableListOf<MemoryRecord>()
        for (path in manifest.files.keys.filter { it.startsWith("${Tier.Working.dir}/") && it.endsWith(".jsonl") }) {
            val r = client.readFile(cfg.owner, cfg.repo, path)
            if (r is Outcome.Ok) {
                r.value.text.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull {
                        runCatching { json.decodeFromString(MemoryRecord.serializer(), it) }.getOrNull()
                    }
                    .forEach { workingRecords.add(it) }
                filesRead.add(path)
            }
        }
        if (workingRecords.isNotEmpty()) {
            val entries = workingRecords.map {
                LearningJournal.Entry(tsMillis = it.t, kind = it.src, note = it.content)
            }
            journal.replaceAll(entries)
            learnings = entries.size
        }

        // settings/preferences.json
        val prefsPath = "settings/preferences.json"
        if (manifest.files.containsKey(prefsPath)) {
            val r = client.readFile(cfg.owner, cfg.repo, prefsPath)
            if (r is Outcome.Ok) {
                val exp = runCatching {
                    manifestJson.decodeFromString(SettingsExport.serializer(), r.value.text)
                }.getOrNull()
                if (exp != null) {
                    appSettings.setRegion(Region.fromId(exp.region))
                    appSettings.setModel(exp.model)
                    settingsOk = true
                    filesRead.add(prefsPath)
                }
            }
        }

        // conversations per-day jsonl
        val chatRowsBuffer = mutableListOf<MessageRow>()
        for (path in manifest.files.keys.filter { it.startsWith("conversations/") && it.endsWith(".jsonl") }) {
            val r = client.readFile(cfg.owner, cfg.repo, path)
            if (r is Outcome.Ok) {
                r.value.text.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull {
                        runCatching { json.decodeFromString(ChatRowExport.serializer(), it) }.getOrNull()
                    }
                    .forEach { row ->
                        chatRowsBuffer.add(
                            MessageRow(
                                tsMillis = row.t,
                                role = row.role,
                                content = row.content,
                                toolCallsJson = row.toolCallsJson,
                                toolCallId = row.toolCallId,
                                name = row.name,
                            ),
                        )
                    }
                filesRead.add(path)
            }
        }
        if (chatRowsBuffer.isNotEmpty()) {
            history.dao.clear()
            history.dao.insertAll(chatRowsBuffer)
            chatRows = chatRowsBuffer.size
        }

        memorySettings.setManifestJson(manifestJson.encodeToString(ManifestV2.serializer(), manifest))
        memorySettings.setLastSyncTs(manifest.lastSyncTsMillis)

        return RestoreReport(
            ok = true,
            message = "Restored $learnings learning(s), $chatRows chat row(s), settings=${if (settingsOk) "ok" else "skipped"}.",
            learningsRestored = learnings,
            chatRowsRestored = chatRows,
            settingsRestored = settingsOk,
            filesRead = filesRead,
        )
    }

    private suspend fun putWithCache(
        client: GitHubClient,
        cfg: MemorySettings.Snapshot,
        path: String,
        body: String,
        manifest: ManifestV2,
        commitMessage: String,
        written: MutableList<String>,
        skipped: MutableList<String>,
        isManifestItself: Boolean = false,
    ) {
        val prev = manifest.files[path]
        if (!isManifestItself && prev?.contentHash == body.hashCode().toString()) {
            skipped.add(path); return
        }
        when (val r = client.writeFile(
            owner = cfg.owner, repo = cfg.repo, path = path,
            text = body, commitMessage = commitMessage, branch = cfg.branch,
            previousSha = prev?.sha,
        )) {
            is Outcome.Ok -> {
                manifest.files[path] = FileEntry(
                    sha = r.value.sha,
                    ts = System.currentTimeMillis(),
                    contentHash = body.hashCode().toString(),
                )
                written.add(path)
            }
            else -> Log.w(tag, "PUT $path failed: $r")
        }
    }

    private fun entryToWorkingRecord(entry: LearningJournal.Entry): MemoryRecord {
        val scrubbed = SecretScrubber.scrub(entry.note)
        val src = "growth:${entry.kind}"
        val facets = buildList {
            add("kind:${entry.kind}")
            add("tier:working")
        }
        return MemoryRecord.working(
            content = scrubbed, src = src, facets = facets,
            ref = null, now = entry.tsMillis,
        )
    }

    /**
     * Bridge file — like agentmemory's "MEMORY.md" + Karpathy LLM-wiki
     * convention. Human-readable digest of what the agent currently
     * "remembers" at the working tier. Will grow to surface promoted
     * semantic facts once M8.3+ extractors land.
     */
    private fun renderMemoryBridge(entries: List<LearningJournal.Entry>, cfg: MemorySettings.Snapshot): String {
        val sorted = entries.sortedByDescending { it.tsMillis }.take(MEMORY_BRIDGE_CAP)
        val sb = StringBuilder()
        sb.append("# Mythara — active memory\n\n")
        sb.append("> Bridge file. Top ${sorted.size} working observations, freshest first.\n")
        sb.append("> Auto-managed by the Mythara Android app. ")
        sb.append("Last sync: ${isoUtc(cfg.lastSyncTs)}\n\n")
        sb.append("---\n\n")
        if (sorted.isEmpty()) {
            sb.append("_(no observations yet — Observe pipeline lands in M8.1+)_\n")
        } else {
            for (e in sorted) {
                val date = isoUtc(e.tsMillis)
                val scrubbed = SecretScrubber.scrub(e.note)
                sb.append("- **$date** · `${e.kind}` — $scrubbed\n")
            }
        }
        sb.append("\n---\n\n")
        sb.append("## Tiers\n\n")
        sb.append("- `working/`     — raw observations (this tier today)\n")
        sb.append("- `episodic/`    — weekly summaries (M8.3+)\n")
        sb.append("- `semantic/`    — durable facts / preferences (M8.3+)\n")
        sb.append("- `procedural/`  — action patterns / workflows (M8.4+)\n")
        return sb.toString()
    }

    private fun isoLocalDate(ms: Long): String {
        val dt = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        return DateTimeFormatter.ISO_LOCAL_DATE.format(dt)
    }

    private fun isoUtc(ms: Long): String {
        if (ms <= 0L) return "never"
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }

    // -------- on-disk shapes (separate from internal Room schema) --------

    @Serializable
    data class ManifestV2(
        var version: Int = CURRENT_VERSION,
        var lastSyncTsMillis: Long = 0,
        val files: MutableMap<String, FileEntry> = mutableMapOf(),
    ) {
        companion object { const val CURRENT_VERSION = 2 }
    }

    @Serializable
    data class FileEntry(val sha: String, val ts: Long, val contentHash: String)

    @Serializable
    data class SettingsExport(val region: String, val model: String)

    @Serializable
    data class ChatRowExport(
        val t: Long,
        val role: String,
        val content: String? = null,
        val toolCallsJson: String? = null,
        val toolCallId: String? = null,
        val name: String? = null,
    )

    companion object {
        private const val README_PATH = "README.md"
        private const val MEMORY_PATH = "MEMORY.md"
        private const val MEMORY_BRIDGE_CAP = 50
        // semantic/ is populated by the vault sync above when there are
        // records; still seeded as a placeholder for fresh repos.
        private val TIER_PLACEHOLDERS = listOf(Tier.Episodic, Tier.Semantic, Tier.Procedural)

        private val PLACEHOLDER_BODY = """
            # placeholder

            This directory is part of Mythara's memory tier layout
            (agentmemory-style). It gets populated when its corresponding
            extractor lands:

            - `episodic/`    — weekly session summaries (M8.3+)
            - `semantic/`    — durable facts and preferences (M8.3+)
            - `procedural/`  — action patterns and workflows (M8.4+)

            Today only `working/` carries records.
        """.trimIndent()

        private val README_BODY = """
            # mythara_memory

            This repository holds the durable state of one or more **Mythara**
            installations. It's managed entirely by the Android app via the
            GitHub Contents API — there is no external service.

            The format mirrors the architectural pattern of
            [agentmemory](https://github.com/rohitg00/agentmemory), adapted for
            mobile: short JSON keys, per-day partitioning, no embeddings stored
            on disk, no graph relations on disk (those reconstruct from
            `facets` + `ref` at recall time).

            ## Layout

            ```
            mythara_memory/
              README.md                          (this file)
              MEMORY.md                          (bridge — top-K active records)
              manifest.json                      (version + per-file SHA cache)
              working/<YYYY-MM-DD>.jsonl         (raw observations)
              episodic/<YYYY-W##>.jsonl          (weekly summaries — M8.3+)
              semantic/facts.jsonl               (durable facts/preferences — M8.3+)
              procedural/workflows.jsonl         (action patterns — M8.4+)
              settings/preferences.json          (region + model + non-secret prefs)
              conversations/<YYYY-MM-DD>.jsonl   (chat history — opt-in)
            ```

            ## Memory record shape (short keys for mobile byte-efficiency)

            ```json
            { "id": "1a2b...-c3d4e5f6",
              "t": 1684004400000,
              "tier": "w",
              "src": "growth:nightly",
              "conf": 1.0,
              "facets": ["topic:python", "kind:preference"],
              "content": "...",
              "sha": "ab12cd34...(24 hex)",
              "ref": "msg:42",
              "seen": 1 }
            ```

            Field meanings:
              - **id**   — ULID-style time-sortable identifier
              - **t**    — epoch millis
              - **tier** — `w` working / `e` episodic / `s` semantic / `p` procedural
              - **src**  — provenance string
              - **conf** — confidence 0..1
              - **facets** — dimension:value tags
              - **content** — short text payload (secrets scrubbed pre-write)
              - **sha**  — 24-char SHA-256 prefix of content; dedup key
              - **ref**  — optional back-link to source event
              - **seen** — reinforcement counter

            ## What is NEVER in this repo

            - MiniMax API key
            - GitHub PAT (this feature's own auth credential)
            - Tink AEAD wrapping key
            - Secret-mode password hash
            - Observe-mode raw audio or transcripts (auto-purged on-device)

            All record content is also run through a regex-based scrubber
            (`SecretScrubber`) that strips anything *shaped like* an API key
            (ghp_, sk-, eyJ, AKIA, xox*) before write.

            ## Compaction (incoming with M8.3+)

            Working observations get promoted into episodic summaries weekly,
            then into semantic facts when reinforced (high `seen` counter),
            then ride the system prompt every chat turn as durable "things I
            know about the user".

            ## Privacy posture

            Trust model: **private GitHub repo + GitHub access control**. The
            content is plaintext JSON — auditable by you, your own
            git-blame-able. No client-side encryption in v1; if you need it,
            change the repo visibility and `repo` scope of the PAT remain the
            only access lever. Open an issue and we'll add passphrase-derived
            AEAD pre-write if the threat model demands it.
        """.trimIndent()
    }
}
