package com.mythara.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hook that normalises filesystem paths before file-touching tools
 * see them. Without this, models routinely emit:
 *   read_file({"path": "~/Downloads/foo.txt"})
 *   read_file({"path": "./notes/today.md"})
 *   write_file({"path": "/sdcard/Documents/x"})
 * — the first fails because `~` isn't expanded inside Kotlin's
 * File("..."); the second hits the current working directory
 * (somewhere under /data/data/...); the third works only because
 * we happen to allow /sdcard.
 *
 * The hook rewrites:
 *   `~`           → app's filesDir absolute path
 *   `~/…`         → filesDir/…
 *   `./…`         → filesDir/…    (treat CWD as filesDir for the agent)
 *   bare `name`   → filesDir/name (no separator at all)
 *
 * Applies to read_file, write_file, list_dir, and the Termux exec
 * tools when they're added.
 */
@Singleton
class PathSanitiserHook @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : HookRunner.ToolHook {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun preToolUse(toolName: String, argsJson: String): HookRunner.Decision {
        if (toolName !in PATH_TOOLS) return HookRunner.Decision.Allow
        val obj = runCatching { json.parseToJsonElement(argsJson) as? JsonObject }
            .getOrNull() ?: return HookRunner.Decision.Allow
        val rawPath = (obj["path"] as? JsonPrimitive)?.contentOrNull()?.trim()
            ?: return HookRunner.Decision.Allow
        val rewritten = sanitise(rawPath) ?: return HookRunner.Decision.Allow
        if (rewritten == rawPath) return HookRunner.Decision.Allow
        val patched = buildJsonObject {
            for ((k, v) in obj) {
                if (k == "path") put(k, JsonPrimitive(rewritten)) else put(k, v)
            }
        }
        return HookRunner.Decision.Rewrite(newArgsJson = patched.toString())
    }

    /** Returns the canonicalised path, or null when nothing needs
     *  changing. Pure — no IO except resolving the app's filesDir
     *  string, which is fast. */
    private fun sanitise(raw: String): String? {
        // content:// URIs are valid for ReadFileTool — pass through.
        if (raw.startsWith("content://") || raw.startsWith("file://")) return raw
        val filesDir = ctx.filesDir.absolutePath
        return when {
            raw == "~" -> filesDir
            raw.startsWith("~/") -> "$filesDir/${raw.removePrefix("~/")}"
            raw.startsWith("./") -> "$filesDir/${raw.removePrefix("./")}"
            raw.startsWith("/") -> raw
            // No separator at all + not absolute → bare filename
            // → treat as relative to filesDir.
            !raw.contains('/') -> "$filesDir/$raw"
            else -> null
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

    companion object {
        private val PATH_TOOLS = setOf(
            "read_file",
            "write_file",
            "list_dir",
        )
    }
}
