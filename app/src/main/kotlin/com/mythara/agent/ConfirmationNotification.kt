package com.mythara.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mythara.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Lock-screen fallback for [ConfirmationGate.request]. When the
 * agent suspends on a destructive tool confirmation (send_sms,
 * send_whatsapp, place_call, etc.) and the in-app dialog can't
 * render (device locked, app backgrounded, screen off), this
 * notification surfaces the prompt with Allow + Deny actions so
 * the user can answer from the lock screen without unlocking the
 * device.
 *
 * Visibility = PUBLIC so the body text + actions render on the
 * lock screen. Auto-cancels when [ConfirmationGate] resolves
 * either via the in-app dialog OR via one of the notification
 * actions.
 */
object ConfirmationNotification {
    private const val TAG = "Mythara/ConfirmNotif"
    private const val CHANNEL_ID = "mythara_confirm"
    private const val CHANNEL_NAME = "Mythara confirmations"
    private const val NOTIFICATION_ID_BASE = 0x4C001

    const val ACTION_CONFIRM = "com.mythara.action.GATE_CONFIRM"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_DECISION = "decision"
    const val DECISION_ALLOW = "allow"
    const val DECISION_DENY = "deny"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Mythara asks for permission before sending a message, " +
                    "placing a call, or running another destructive action."
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                enableVibration(true)
            },
        )
    }

    /** Hash the request id into a stable int notification id so the
     *  matching [cancel] call hits the right notification. */
    private fun notificationIdFor(requestId: String): Int =
        NOTIFICATION_ID_BASE + (requestId.hashCode() and 0x7fffffff) % 9999

    fun post(ctx: Context, req: ConfirmationGate.ConfirmRequest) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val notifId = notificationIdFor(req.id)

        fun action(decision: String, label: String): NotificationCompat.Action {
            val intent = Intent(ctx, ConfirmationActionReceiver::class.java).apply {
                action = ACTION_CONFIRM
                putExtra(EXTRA_REQUEST_ID, req.id)
                putExtra(EXTRA_DECISION, decision)
                `package` = ctx.packageName
            }
            // Unique request code per (id, decision) so the
            // FLAG_UPDATE_CURRENT-paired intents don't collide.
            val reqCode = (req.id + decision).hashCode()
            val pi = PendingIntent.getBroadcast(
                ctx,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Action.Builder(0, label, pi).build()
        }

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(req.title.ifBlank { "Mythara needs permission" })
            .setContentText(req.body.takeIf { it.isNotBlank() } ?: "Allow ${req.toolName}?")
            .setStyle(NotificationCompat.BigTextStyle().bigText(req.body))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOngoing(false)
            .addAction(action(DECISION_ALLOW, "Allow"))
            .addAction(action(DECISION_DENY, "Deny"))
            .setTimeoutAfter(NOTIFICATION_TTL_MS)
            .build()
        nm.notify(notifId, notif)
        Log.d(TAG, "posted fallback prompt for ${req.id} tool=${req.toolName}")
    }

    fun cancel(ctx: Context, requestId: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(notificationIdFor(requestId))
    }

    /** Notification self-cancels after this many ms so a stale prompt
     *  doesn't sit on the lock screen forever. Should align with
     *  the upstream turn timeout (90 s) — slightly shorter so the
     *  turn gets the timeout error from the gate rather than the
     *  notification disappearing silently. */
    private const val NOTIFICATION_TTL_MS = 85_000L
}

/**
 * Receives Allow / Deny taps from [ConfirmationNotification]'s
 * actions and forwards the decision into [ConfirmationGate.resolve],
 * which unblocks the suspended tool call. Uses Hilt
 * `@AndroidEntryPoint` so the gate singleton is injected — keeping
 * the resolution path on the SAME singleton the suspending tool
 * is awaiting on.
 */
@AndroidEntryPoint
class ConfirmationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var gate: ConfirmationGate

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConfirmationNotification.ACTION_CONFIRM) return
        val id = intent.getStringExtra(ConfirmationNotification.EXTRA_REQUEST_ID)
            ?: return
        val decision = when (intent.getStringExtra(ConfirmationNotification.EXTRA_DECISION)) {
            ConfirmationNotification.DECISION_ALLOW -> ConfirmationGate.Decision.Allow
            ConfirmationNotification.DECISION_DENY -> ConfirmationGate.Decision.Deny
            else -> return
        }
        Log.d("Mythara/ConfirmRecv", "resolve id=$id decision=$decision (from notification)")
        gate.resolve(id, decision)
        // Cancel the notification explicitly — Android usually does
        // it on setAutoCancel=true but the user tapping an action
        // doesn't always count as a "tap" depending on launcher.
        ConfirmationNotification.cancel(context, id)
    }
}
