package com.mythara.agent

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-call confirmation gate for destructive tool invocations.
 *
 * Architecture is a request/response pair across the agent loop and
 * the chat UI:
 *
 *   AgentLoop                            ChatScreen
 *   ─────────                            ──────────
 *   ToolRegistry.execute(call)
 *      └─► gate.request(ConfirmRequest)
 *               (suspends here)
 *                     emit on pendingRequests SharedFlow ──►
 *                                                          render Dialog
 *                                                          user taps Allow / Deny
 *                                                     ◄── gate.resolve(id, accepted)
 *      ◄── resumes with Decision
 *   if accepted → tool.execute(args)
 *   else        → ToolResult.fail("user_canceled")
 *
 * The gate is process-wide singleton (Hilt @Singleton). One in-flight
 * request at a time keeps things simple — if a second comes in while
 * a first is pending we queue it; the dialog shows them in order.
 *
 * Auto-allow path: callers can pass `allowlistKey` (e.g.
 * `"send_sms_direct"` or `"call:+15551234"`) into the request; the
 * [com.mythara.data.AllowlistStore] short-circuits if the user has
 * checked "Always allow this" on a prior prompt.
 */
@Singleton
class ConfirmationGate @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    /** Local scope for the lock-screen notification fallback timer.
     *  SupervisorJob so a cancelled request doesn't tear down others. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class Decision { Allow, Deny }

    /**
     * A pending confirmation prompt the UI should render. The UI
     * calls [ConfirmationGate.resolve] with the matching [id] when
     * the user taps an action.
     */
    data class ConfirmRequest(
        val id: String,
        val toolName: String,
        val title: String,
        val body: String,
        /**
         * Optional key. When set, the UI also shows an
         * "Always allow this" checkbox; when ticked, the answer is
         * persisted via [com.mythara.data.AllowlistStore] so future
         * calls with the same key skip the prompt entirely. Null
         * means single-shot only (e.g. one-time payment).
         */
        val allowlistKey: String? = null,
        /**
         * Set when this prompt is a critical-action gate for a specific
         * app package (ride-hailing, e-commerce, …). When non-null the
         * UI also shows the "always allow" toggle — ticking it calls
         * [com.mythara.data.RestrictedAppsStore.removeCritical] so the
         * app is de-listed from critical and never prompts again.
         */
        val criticalPkg: String? = null,
    )

    private val _pending = MutableSharedFlow<ConfirmRequest>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pending: SharedFlow<ConfirmRequest> = _pending.asSharedFlow()

    // id -> waiting completion. Resolved by [resolve] on UI input.
    private val inflight = mutableMapOf<String, CompletableDeferred<Decision>>()

    /** Last unresolved request, exposed so UI can re-render after a recompose. */
    @Volatile var currentRequest: ConfirmRequest? = null
        private set

    /**
     * Ask the user to confirm. Suspends until the UI resolves with
     * Allow / Deny. If the chat surface isn't visible (settings screen,
     * auth gate, app backgrounded) the request still queues — the
     * dialog shows on next composition of ChatScreen.
     *
     * Caller must ensure they're not on the main thread (we use
     * a `CompletableDeferred.await()` here which suspends — fine to
     * call from anywhere coroutine-aware).
     */
    suspend fun request(req: ConfirmRequest): Decision {
        val deferred = CompletableDeferred<Decision>()
        synchronized(inflight) { inflight[req.id] = deferred }
        currentRequest = req
        Log.d(TAG, "request ${req.id} tool=${req.toolName} key=${req.allowlistKey}")
        _pending.tryEmit(req)
        // Background fallback: after a short grace period, if no UI
        // has resolved this request yet, post a lock-screen
        // notification with Allow + Deny actions. Lets the user
        // approve / deny destructive tool calls from the lock screen
        // without opening the chat surface — which is exactly what
        // happens when the agent auto-triages an incoming message
        // while the device is locked or backgrounded.
        val fallbackJob = scope.launch {
            delay(NOTIFICATION_FALLBACK_MS)
            // Re-check inflight under lock — UI may have resolved
            // during the delay.
            val stillPending = synchronized(inflight) { req.id in inflight }
            if (stillPending) {
                runCatching { ConfirmationNotification.post(ctx, req) }
                    .onFailure { Log.w(TAG, "fallback notif failed: ${it.message}") }
            }
        }
        return try {
            deferred.await()
        } finally {
            // Cancel pending fallback + clear any posted notification.
            runCatching { fallbackJob.cancel() }
            runCatching { ConfirmationNotification.cancel(ctx, req.id) }
            synchronized(inflight) { inflight.remove(req.id) }
            // Clear currentRequest only if it's still the one we just
            // resolved — a second concurrent request could have moved
            // it forward already.
            if (currentRequest?.id == req.id) currentRequest = null
        }
    }

    /** Called by the UI when the user taps Allow / Deny. */
    fun resolve(id: String, decision: Decision) {
        val deferred = synchronized(inflight) { inflight[id] }
        if (deferred == null) {
            Log.w(TAG, "resolve $id $decision but no inflight match")
            return
        }
        Log.d(TAG, "resolve $id $decision")
        deferred.complete(decision)
    }

    /** Cheap ID generator — collision-free within a process lifetime. */
    fun newId(toolName: String): String =
        "$toolName:${System.nanoTime().toString(36)}"

    companion object {
        private const val TAG = "Mythara/Gate"

        /** Grace window before falling back to the lock-screen
         *  notification. The chat UI usually picks up the
         *  _pending emission within hundreds of ms when it's
         *  visible; the 4-second wait avoids posting a
         *  notification that flashes for half a second and gets
         *  immediately cancelled when the in-app dialog appears. */
        const val NOTIFICATION_FALLBACK_MS = 4_000L
    }
}
