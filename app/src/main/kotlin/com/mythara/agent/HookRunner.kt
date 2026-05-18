package com.mythara.agent

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-execution middleware for every tool invocation. Ported from
 * Crush's `internal/agent/hooked_tool.go` pattern.
 *
 * Hooks run BEFORE [ToolRegistry.execute] and may:
 *   - Allow the tool call to proceed unchanged ([Decision.Allow])
 *   - Deny the call with a structured error ([Decision.Deny])
 *   - Rewrite the arguments before execution ([Decision.Rewrite])
 *
 * The runner walks every registered hook in order and applies the
 * first non-Allow decision. Allow is the default — if no hook
 * objects, the call goes through.
 *
 * Used today for:
 *   - [PathSanitiserHook] — rewrite `~/foo` → absolute paths so
 *     read_file / write_file / list_dir work consistently regardless
 *     of how the model spells the path.
 *   - [DangerousShellHook] — deny obviously-destructive shell
 *     patterns (rm -rf /, dd, mkfs, chmod 777 /) before they reach
 *     run_shell / termux_exec.
 *
 * Future hooks (planned in the v4 plan):
 *   - AutoApproveHook — consult AllowlistStore so previously-
 *     approved (tool, args-shape) pairs skip the ConfirmationGate.
 *   - LoopDetector wrap — though that's handled per-turn in
 *     AgentLoop, not as a hook.
 */
@Singleton
class HookRunner @Inject constructor(
    private val pathSanitiser: PathSanitiserHook,
    private val dangerousShell: DangerousShellHook,
) {

    sealed interface Decision {
        data object Allow : Decision
        data class Rewrite(val newArgsJson: String) : Decision
        data class Deny(val reason: String) : Decision
    }

    interface ToolHook {
        suspend fun preToolUse(toolName: String, argsJson: String): Decision
    }

    private val hooks: List<ToolHook> = listOf(
        pathSanitiser,
        dangerousShell,
    )

    /**
     * Walk all hooks in order. The FIRST non-Allow decision wins:
     *   - Deny  → halt immediately, return its reason
     *   - Rewrite → swap args, keep walking remaining hooks against
     *               the rewritten args
     *   - Allow → continue to the next hook
     *
     * Returns the final (possibly rewritten) args plus the final
     * decision. Caller (AgentLoop's tool-execution block) inspects
     * the decision: if Deny, synthesise a ToolResult.fail; else
     * call registry.execute with the (possibly rewritten) args.
     */
    suspend fun run(toolName: String, argsJson: String): Result {
        var current = argsJson
        for (hook in hooks) {
            when (val d = hook.preToolUse(toolName, current)) {
                is Decision.Allow -> { /* keep walking */ }
                is Decision.Rewrite -> {
                    Log.d(TAG, "${hook.javaClass.simpleName} rewrote $toolName args")
                    current = d.newArgsJson
                }
                is Decision.Deny -> {
                    Log.w(TAG, "${hook.javaClass.simpleName} denied $toolName: ${d.reason}")
                    return Result(args = current, denied = true, reason = d.reason)
                }
            }
        }
        return Result(args = current, denied = false, reason = null)
    }

    data class Result(
        val args: String,
        val denied: Boolean,
        val reason: String?,
    )

    companion object {
        private const val TAG = "Mythara/HookRunner"
    }
}
