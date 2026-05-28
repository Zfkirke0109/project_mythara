package com.mythara.people

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Launch helpers for the per-contact actions on the People screen.
 * Reuses the intent patterns proven by the agent's `place_call_direct`,
 * `send_sms_direct`, `send_whatsapp_direct` tools — the same code
 * paths Mythara herself uses from chat — so the People screen has the
 * exact same launch reliability.
 *
 * Phone-direct call uses `ACTION_CALL` (placing the call immediately)
 * which needs `CALL_PHONE`. That permission is declared in the
 * manifest; we runtime-check here and fall back to `ACTION_DIAL`
 * (which opens the dialer pre-filled — no permission needed) if it's
 * been denied.
 */
object ContactActions {

    private const val TAG = "Mythara/ContactActions"

    /** Place a direct phone call (ACTION_CALL when allowed; fallback
     *  to ACTION_DIAL which opens the dialer ready-to-call). */
    fun phoneCall(ctx: Context, number: String) {
        val clean = number.trim()
        if (clean.isBlank()) return
        val uri = Uri.parse("tel:$clean")
        val canCall = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(
            if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            uri,
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { ctx.startActivity(intent) }
            .onFailure { Log.w(TAG, "phone-call launch failed: ${it.message}") }
    }

    /** Open the SMS composer with the recipient pre-filled. */
    fun sms(ctx: Context, number: String) {
        val clean = number.trim()
        if (clean.isBlank()) return
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$clean")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
            .onFailure { Log.w(TAG, "sms launch failed: ${it.message}") }
    }

    /** Open the WhatsApp chat for [number]. Tries the whatsapp://
     *  scheme first (faster, no browser hop); falls back to wa.me. */
    fun whatsAppChat(ctx: Context, number: String) {
        val e164 = number.replace(Regex("[^+0-9]"), "")
        val noPlus = e164.removePrefix("+")
        if (noPlus.isBlank()) return
        // Direct app intent — opens the chat without going through a
        // browser. Requires WhatsApp to be installed.
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$noPlus")).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching { ctx.startActivity(app); true }.getOrDefault(false)
        if (ok) return
        // Fallback — wa.me universal link (opens via browser → WhatsApp).
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$noPlus")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(web) }
            .onFailure { Log.w(TAG, "whatsapp launch failed: ${it.message}") }
    }

    /** Open the WhatsApp chat — user taps the call icon there.
     *  WhatsApp does not expose a clean public "place voice call"
     *  intent; the chat is the most reliable entry. */
    fun whatsAppCall(ctx: Context, number: String) {
        whatsAppChat(ctx, number)
    }
}
