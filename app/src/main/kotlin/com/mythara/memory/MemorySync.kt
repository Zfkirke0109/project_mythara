package com.mythara.memory

import android.util.Log
import com.mythara.data.HistoryRepository
import com.mythara.data.MessageRow
import com.mythara.data.SettingsStore
import com.mythara.growth.LearningJournal
import com.mythara.memory.github.GitHubClient
import com.mythara.memory.github.GitHubClient.Outcome
import com.mythara.minimax.Region
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the push from local Mythara state → the user's GitHub
 * memory repo. Single entry point: [runSync]. Returns a [Report] so the
 * caller (Settings panel "Sync now", or the nightly WorkManager job)
 * can surface a useful summary.
 *
 * Today this writes:
 *  - learnings/journal.jsonl       — every LearningJournal entry (M8.0 stub)
 *  - settings/preferences.json     — non-secret prefs (region, model)
 *  - conversations/YYYY-MM-DD.jsonl — chat history, partitioned by day
 *                                    (only if user opts in; OFF by default)
 *  - manifest.json                  — sha cache + last-sync timestamp
 *
 * Each user-data file maps one-to-one to a local snapshot; we don't try
 * to compute diffs — for the sizes involved (kilobytes), rewriting the
 * file each sync is simpler and the GitHub Contents API handles
 * "no-change → same sha" gracefully.
 *
 * Privacy invariants enforced here:
 *  - The MiniMax API key, GitHub PAT, Tink keyset, Secret-mode password,
 *    and any Observe raw audio/transcripts NEVER appear in any file we
 *    write. The categories above are the only ones synced.
 */
@Singleton
class MemorySync @Inject constructor(
    private val memorySettings: MemorySettings,
    private val journal: LearningJournal,
    private val appSettings: SettingsStore,
    private val history: HistoryRepository,
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
    private val json = Json { encodeDefaults = false; prettyPrint = true; ignoreUnknownKeys = true }

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

        // Load (or initialise) the per-file sha cache from MemorySettings.
        val manifest: ManifestV1 = cfg.manifestJson?.let {
            runCatching { json.decodeFromString(ManifestV1.serializer(), it) }.getOrNull()
        } ?: ManifestV1()

        val now = System.currentTimeMillis()
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        // ---- README.md (one-time documentation, ensures repo isn't empty)
        if (forcePush || !manifest.files.containsKey(README_PATH)) {
            putWithCache(client, cfg, README_PATH, README_BODY, manifest, "mythara: seed README", written, skipped)
        }

        // ---- learnings/journal.jsonl
        if (cfg.syncLearnings) {
            val entries = journal.read()
            val body = entries.joinToString("\n") {
                json.encodeToString(LearningJournal.Entry.serializer(), it)
            }
            putWithCache(client, cfg, "learnings/journal.jsonl", body, manifest,
                "mythara: sync journal (${entries.size} entries)", written, skipped)
        }

        // ---- settings/preferences.json (non-secret prefs only)
        if (cfg.syncSettings) {
            val snap = appSettings.snapshot()
            val obj = SettingsExport(region = snap.region.name, model = snap.model)
            val body = json.encodeToString(SettingsExport.serializer(), obj)
            putWithCache(client, cfg, "settings/preferences.json", body, manifest,
                "mythara: sync settings", written, skipped)
        }

        // ---- conversations/<date>.jsonl  (opt-in)
        if (cfg.syncChat) {
            val rows = history.dao.listAll()
            val byDay = rows.groupBy { rowToLocalDate(it.tsMillis) }
            for ((day, dayRows) in byDay) {
                val body = dayRows.joinToString("\n") {
                    json.encodeToString(ChatRowExport.serializer(), ChatRowExport(
                        ts = it.tsMillis,
                        role = it.role,
                        content = it.content,
                        toolCallsJson = it.toolCallsJson,
                        toolCallId = it.toolCallId,
                        name = it.name,
                    ))
                }
                val path = "conversations/$day.jsonl"
                putWithCache(client, cfg, path, body, manifest,
                    "mythara: sync conversation $day", written, skipped)
            }
        }

        // ---- manifest.json (always last)
        manifest.lastSyncTsMillis = now
        manifest.version = ManifestV1.CURRENT_VERSION
        val manifestBody = json.encodeToString(ManifestV1.serializer(), manifest)
        putWithCache(client, cfg, "manifest.json", manifestBody, manifest,
            "mythara: manifest @ ${isoUtc(now)}", written, skipped, isManifestItself = true)

        memorySettings.setLastSyncTs(now)
        memorySettings.setManifestJson(json.encodeToString(ManifestV1.serializer(), manifest))

        return Report(
            ok = true,
            message = "Synced ${written.size} file(s) to ${cfg.owner}/${cfg.repo}.",
            filesWritten = written,
            skipped = skipped,
        )
    }

    /**
     * Pull from the memory repo and materialise into local stores. Semantics
     * are REPLACE — the user explicitly confirms before this runs, and the
     * point is to bring a fresh device back to the canonical state.
     *
     * Order matters:
     *   1. manifest.json — drives the file list. If missing, the repo is
     *      uninitialised; nothing to restore.
     *   2. learnings/journal.jsonl — `LearningJournal.replaceAll`
     *   3. settings/preferences.json — SettingsStore.setRegion + setModel
     *   4. conversations per-day jsonl — chat history, bulk insert into Room
     *
     * The API key is *not* restored — it's intentionally per-device. The
     * user re-enters it once on the new phone.
     */
    suspend fun runRestore(): RestoreReport {
        val cfg = memorySettings.snapshot()
        if (!cfg.configured) return RestoreReport(ok = false, message = "Set a GitHub token + repo in Settings first.")

        val client = GitHubClient(cfg.pat!!)
        when (val v = client.validateToken()) {
            is Outcome.Ok -> Log.d(tag, "PAT ok for ${v.value} (restore)")
            is Outcome.Unauthorized -> return RestoreReport(ok = false, message = "GitHub token rejected.")
            else -> return RestoreReport(ok = false, message = "GitHub auth check failed.")
        }

        // 1. Read manifest
        val manifestPath = "manifest.json"
        val manifestRead = client.readFile(cfg.owner, cfg.repo, manifestPath)
        if (manifestRead is Outcome.NotFound) {
            return RestoreReport(ok = false, message = "Repo has no manifest — nothing to restore yet.")
        }
        if (manifestRead !is Outcome.Ok) {
            return RestoreReport(ok = false, message = "Could not read manifest from repo.")
        }
        val manifest = runCatching {
            json.decodeFromString(ManifestV1.serializer(), manifestRead.value.text)
        }.getOrElse { return RestoreReport(ok = false, message = "Manifest is malformed.") }

        var learnings = 0
        var chatRows = 0
        var settingsOk = false
        val filesRead = mutableListOf(manifestPath)

        // 2. learnings/journal.jsonl
        val journalPath = "learnings/journal.jsonl"
        if (manifest.files.containsKey(journalPath)) {
            val r = client.readFile(cfg.owner, cfg.repo, journalPath)
            if (r is Outcome.Ok) {
                val entries = r.value.text.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull {
                        runCatching { json.decodeFromString(LearningJournal.Entry.serializer(), it) }.getOrNull()
                    }
                    .toList()
                journal.replaceAll(entries)
                learnings = entries.size
                filesRead.add(journalPath)
            }
        }

        // 3. settings/preferences.json
        val prefsPath = "settings/preferences.json"
        if (manifest.files.containsKey(prefsPath)) {
            val r = client.readFile(cfg.owner, cfg.repo, prefsPath)
            if (r is Outcome.Ok) {
                val exp = runCatching {
                    json.decodeFromString(SettingsExport.serializer(), r.value.text)
                }.getOrNull()
                if (exp != null) {
                    appSettings.setRegion(Region.fromId(exp.region))
                    appSettings.setModel(exp.model)
                    settingsOk = true
                    filesRead.add(prefsPath)
                }
            }
        }

        // 4. conversations per-day jsonl — restore every day file in the manifest
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
                                tsMillis = row.ts,
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

        // Update local manifest cache so next sync uses the right SHAs.
        memorySettings.setManifestJson(json.encodeToString(ManifestV1.serializer(), manifest))
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
        manifest: ManifestV1,
        commitMessage: String,
        written: MutableList<String>,
        skipped: MutableList<String>,
        isManifestItself: Boolean = false,
    ) {
        val prev = manifest.files[path]
        // Skip identical re-writes (saves a useless commit) unless this is
        // the manifest itself — manifest must always rewrite because its
        // lastSyncTs changed.
        if (!isManifestItself && prev?.contentHash == body.hashCode().toString()) {
            skipped.add(path); return
        }
        when (val r = client.writeFile(
            owner = cfg.owner, repo = cfg.repo, path = path,
            text = body, commitMessage = commitMessage, branch = cfg.branch,
            previousSha = prev?.sha,
        )) {
            is Outcome.Ok -> {
                manifest.files[path] = FileEntry(sha = r.value.sha, ts = System.currentTimeMillis(), contentHash = body.hashCode().toString())
                written.add(path)
            }
            else -> Log.w(tag, "PUT $path failed: $r")
        }
    }

    private fun rowToLocalDate(ms: Long): String {
        // ISO local date — same key as filename. SimpleDateFormat is safer than
        // java.time.LocalDate on really old API levels but min API 26 means we
        // can use java.time freely.
        val dt = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        return DateTimeFormatter.ISO_LOCAL_DATE.format(dt)
    }

    private fun isoUtc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }

    // -------- export shapes (intentionally separate from internal Room schema) --------

    @Serializable
    data class ManifestV1(
        var version: Int = CURRENT_VERSION,
        var lastSyncTsMillis: Long = 0,
        val files: MutableMap<String, FileEntry> = mutableMapOf(),
    ) {
        companion object { const val CURRENT_VERSION = 1 }
    }

    @Serializable
    data class FileEntry(val sha: String, val ts: Long, val contentHash: String)

    @Serializable
    data class SettingsExport(val region: String, val model: String)

    @Serializable
    data class ChatRowExport(
        val ts: Long,
        val role: String,
        val content: String? = null,
        val toolCallsJson: String? = null,
        val toolCallId: String? = null,
        val name: String? = null,
    )

    companion object {
        private const val README_PATH = "README.md"
        private val README_BODY = """
            # mythara_memory

            This repository holds the durable state of one or more **Mythara**
            installations — the learnings the app has built up about its user,
            the non-secret settings, and (opt-in) the chat history. It's
            managed entirely by the Android app via the GitHub Contents API.

            ## Layout

            - `learnings/journal.jsonl` — append-only log of `LearningJournal` entries.
            - `settings/preferences.json` — region + model + non-secret prefs.
            - `conversations/<YYYY-MM-DD>.jsonl` — chat history, partitioned by local day.
              Only present if the user opted into chat-history sync in Settings.
            - `manifest.json` — version + per-file SHA cache + last-sync timestamp.

            ## What is **never** in this repo

            - MiniMax API key
            - GitHub Personal Access Token
            - Tink AEAD wrapping key
            - Secret-mode password hash
            - Observe-mode raw audio or transcripts (auto-purged on-device)

            Secrets stay per-device by design. Switching devices means re-entering
            both API keys; the learnings come back via this repo.
        """.trimIndent()
    }
}
