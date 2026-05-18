package com.mythara.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hook that denies obviously-destructive shell patterns BEFORE
 * they reach run_shell / termux_exec. The shell-tool allowlist
 * already caps what binaries can run, but a model in trouble can
 * still combine allowed binaries into destructive operations
 * (e.g. `find / -delete`, `find / -exec rm {} \;`). This is the
 * second line of defence.
 *
 * Pattern philosophy:
 *   - DENY ONLY on extremely high-confidence destructive shapes
 *     (rm -rf, dd, mkfs, format-shaped commands).
 *   - All other commands fall through to the existing allowlist
 *     + ConfirmationGate mechanisms.
 *   - Reasons are human-readable so the model can self-correct
 *     in the next iteration ("I tried to rm -rf, blocked,
 *     let me list contents instead").
 *
 * Currently scoped to `run_shell` and the future `termux_exec`
 * tools. Add additional tool names to [SHELL_TOOLS] when more
 * exec surfaces land.
 */
@Singleton
class DangerousShellHook @Inject constructor() : HookRunner.ToolHook {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun preToolUse(toolName: String, argsJson: String): HookRunner.Decision {
        if (toolName !in SHELL_TOOLS) return HookRunner.Decision.Allow
        val obj = runCatching { json.parseToJsonElement(argsJson) as? JsonObject }
            .getOrNull() ?: return HookRunner.Decision.Allow

        // Most shell tools take cmd + args; some take a single
        // `command` string. Build a single canonical "full command
        // line" view to pattern-match against.
        val cmd = (obj["cmd"] as? JsonPrimitive)?.contentOrNull()
            ?: (obj["command"] as? JsonPrimitive)?.contentOrNull()
            ?: ""
        val args = (obj["args"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
            ?: emptyList()
        val full = (listOf(cmd) + args).joinToString(" ").trim().lowercase()
        if (full.isBlank()) return HookRunner.Decision.Allow

        for (pattern in DENY_PATTERNS) {
            if (pattern.regex.containsMatchIn(full)) {
                return HookRunner.Decision.Deny(reason = pattern.reason)
            }
        }
        return HookRunner.Decision.Allow
    }

    private data class DenyPattern(val regex: Regex, val reason: String)

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

    companion object {
        private val SHELL_TOOLS = setOf(
            "run_shell",
            "termux_exec",
        )

        // Conservative: only the unambiguously-destructive shapes.
        // Each entry includes a short, agent-readable reason so the
        // model can adapt on the next iteration (e.g. "use ls
        // instead of find -delete").
        private val DENY_PATTERNS = listOf(
            DenyPattern(
                Regex("""\brm\s+(-[a-z]*r[a-z]*f|-rf|-fr|--recursive\s+--force|--force\s+--recursive)\b"""),
                "blocked_destructive: rm -rf / -fr is denied — use the file managers in the UI " +
                    "or be explicit per-file with `rm <path>`.",
            ),
            DenyPattern(
                Regex("""\bdd\s+(if=|of=)"""),
                "blocked_destructive: `dd` blanket-blocked — direct device I/O can brick storage.",
            ),
            DenyPattern(
                Regex("""\bmkfs(\.|\s)"""),
                "blocked_destructive: `mkfs` denied — would reformat a filesystem.",
            ),
            DenyPattern(
                Regex("""\bmke?2fs\b"""),
                "blocked_destructive: filesystem-creation denied.",
            ),
            DenyPattern(
                Regex("""\bchmod\s+(-r\s+)?777\s+/"""),
                "blocked_destructive: chmod 777 / would open the whole filesystem.",
            ),
            DenyPattern(
                Regex("""\bfind\s+/.*(-delete\b|-exec\s+rm\b)"""),
                "blocked_destructive: find / with -delete or -exec rm would walk the root " +
                    "filesystem and delete entries.",
            ),
            DenyPattern(
                Regex("""\bshutdown\b|\breboot\b"""),
                "blocked_destructive: shutdown / reboot are not appropriate from the agent. " +
                    "Suggest the action to the user instead.",
            ),
            DenyPattern(
                Regex(""":\s*\(\)\s*\{[^}]*\|\s*:[^}]*\}\s*;\s*:"""),
                "blocked_destructive: fork-bomb pattern.",
            ),
        )
    }
}
