package com.mythara.agent

import android.util.Log
import java.security.MessageDigest

/**
 * Per-turn guardrail against stuck tool-call cycles. Ported from
 * Crush's `internal/agent/loop_detection.go` pattern.
 *
 * The agent occasionally gets into a degenerate state where it
 * keeps invoking the same tool with the same arguments and getting
 * the same result — most commonly with smaller models that have
 * weaker self-awareness ("read file X" → "edit file X" → "read
 * file X" → "edit file X" → …). MAX_ITERATIONS is the hard ceiling
 * (8 main / 5 subagent) but eats 8 round-trips of MiniMax compute
 * before halting.
 *
 * LoopDetector watches a rolling window of (toolName, argsHash,
 * resultHash) signatures and trips when the same signature recurs
 * more than [REPEAT_THRESHOLD] times. The agent loop then emits a
 * Turn.Error and breaks without burning the rest of the iteration
 * budget.
 *
 * Per-turn lifetime — a fresh instance is constructed at the top
 * of every AgentLoop.submit(). Cheap to allocate, no persistent
 * state across turns.
 */
class LoopDetector {
    private val window: ArrayDeque<String> = ArrayDeque(WINDOW_SIZE)
    private val md = MessageDigest.getInstance("SHA-256")

    /**
     * Record one tool execution. Returns true when this execution
     * repeats a signature that's already appeared > REPEAT_THRESHOLD
     * times in the window — i.e. the loop is stuck and the caller
     * should halt.
     */
    fun record(toolName: String, argsJson: String, resultBody: String): Boolean {
        val sig = signature(toolName, argsJson, resultBody)
        if (window.size >= WINDOW_SIZE) window.removeFirst()
        window.addLast(sig)
        val repeats = window.count { it == sig }
        val stuck = repeats > REPEAT_THRESHOLD
        if (stuck) {
            Log.w(
                TAG,
                "loop detected: tool=$toolName signature=$sig repeated " +
                    "$repeats times in last ${window.size} steps — halting turn",
            )
        }
        return stuck
    }

    /** Compute a stable signature for one tool execution.
     *  Tool name + args + result are hashed together so identical
     *  work produces identical signatures. We keep only the first
     *  16 hex chars to keep memory bounded across the window. */
    private fun signature(toolName: String, argsJson: String, resultBody: String): String {
        synchronized(md) {
            md.reset()
            md.update(toolName.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(argsJson.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(resultBody.toByteArray(Charsets.UTF_8))
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            return "$toolName:" + hex.take(16)
        }
    }

    companion object {
        private const val TAG = "Mythara/LoopDetector"

        /** Sliding window — large enough to catch a real cycle
         *  (read→edit→read→edit) without eating a legitimate
         *  batch loop (e.g. "summarise these 10 files"). */
        const val WINDOW_SIZE = 12

        /** Trip when the same signature recurs more than this
         *  many times within the window. 4 = "we've now done the
         *  exact same work 5 times in a row" — clearly stuck. */
        const val REPEAT_THRESHOLD = 4
    }
}
