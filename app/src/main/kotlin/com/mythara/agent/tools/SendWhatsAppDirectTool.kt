package com.mythara.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mythara.agent.ConfirmationGate
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.services.PhoneControlAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `send_whatsapp_direct` — send a WhatsApp message without leaving
 * Mythara in the user's hands. The agent drives WhatsApp through
 * Accessibility automation: opens the wa.me deep-link (which lands
 * directly in a chat with the message pre-filled), waits for the
 * UI to settle, taps the send button via Accessibility, then
 * brings Mythara back to the foreground.
 *
 * The user sees a brief WhatsApp flash (~2s) then is back in
 * Mythara with Lumi confirming. Compare to `send_whatsapp` (the
 * composer variant) which dumps the user inside WhatsApp with a
 * pre-filled draft and they have to tap Send + navigate back
 * themselves.
 *
 * Requirements:
 *  - PhoneControlAccessibilityService granted (Settings →
 *    Accessibility → Mythara). Without it the tool fails fast
 *    and the model can fall back to send_whatsapp.
 *  - WhatsApp installed (com.whatsapp).
 *  - Phone number in E.164 with country code (wa.me strips '+').
 *
 * No public WhatsApp API exists for true zero-UI sending — this is
 * the closest you can get without write-overlay-permission tricks.
 *
 * Gated by ConfirmationGate per the per-call rules, but when the
 * global "always confirm" toggle is off (default), this fires
 * immediately like every other direct-send.
 */
@Singleton
class SendWhatsAppDirectTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    override val name: String = "send_whatsapp_direct"
    override val description: String =
        "Send a WhatsApp message WITHOUT leaving the user staring at WhatsApp. " +
            "Mythara opens WhatsApp briefly (~2 seconds), auto-fills the message, " +
            "auto-taps Send via the Accessibility service, then returns Mythara " +
            "to the foreground. " +
            "Prefer this over `send_whatsapp` whenever the user explicitly said 'send'/'message'/'whatsapp X Y' " +
            "(active intent) rather than 'compose'/'draft' (passive). " +
            "Requires the user to have granted Mythara's Accessibility service. " +
            "Falls back to send_whatsapp (composer) on accessibility-not-granted."

    /**
     * Driving another app's UI counts as destructive — same gate
     * shape as other direct-send tools. Allowlist key is per-number
     * so granting "always allow" to mom doesn't grant it for
     * everyone.
     */
    override val requiresConfirmation: Boolean = true

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "to",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Recipient phone number in E.164 (e.g. +14155551234). Country code REQUIRED — wa.me can't resolve local-format numbers.",
                        )
                    },
                )
                put(
                    "body",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Message body. The user won't see it before send.")
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("to"), JsonPrimitive("body"))))
    }

    override fun confirmationFor(args: JsonObject): ConfirmationGate.ConfirmRequest? {
        val to = (args["to"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val body = (args["body"] as? JsonPrimitive)?.content.orEmpty()
        return ConfirmationGate.ConfirmRequest(
            id = "",
            toolName = name,
            title = "WhatsApp $to?",
            body = if (body.length <= PREVIEW_CHARS) body else "${body.take(PREVIEW_CHARS)}…",
            allowlistKey = if (to.isNotEmpty()) "${name}:$to" else null,
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val to = (args["to"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val body = (args["body"] as? JsonPrimitive)?.content.orEmpty()
        if (to.isEmpty()) return ToolResult(false, """{"error":"missing_to"}""")
        if (body.isEmpty()) return ToolResult(false, """{"error":"missing_body"}""")

        val service = PhoneControlAccessibilityService.instance
            ?: return ToolResult(
                ok = false,
                output = """{"error":"accessibility_not_granted","detail":"Enable Mythara in Settings → Accessibility. The composer-variant send_whatsapp works without it."}""",
            )

        // 1. Launch WhatsApp with the message pre-filled via wa.me.
        //    Strip the '+' from the phone — wa.me URLs want bare digits.
        val phoneDigits = to.filter { it.isDigit() }
        val encodedBody = URLEncoder.encode(body, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("whatsapp://send?phone=$phoneDigits&text=$encodedBody")
            setPackage(WHATSAPP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { ctx.startActivity(intent) }.isSuccess
        if (!launched) {
            // Fallback to wa.me HTTPS URL (still resolves to WhatsApp
            // via Android's intent picker when installed).
            val webIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/$phoneDigits?text=$encodedBody")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!runCatching { ctx.startActivity(webIntent) }.isSuccess) {
                return ToolResult(false, """{"error":"whatsapp_unavailable","detail":"Couldn't open WhatsApp. Is it installed?"}""")
            }
        }

        // 2. Wait for the chat surface to render + the send button
        //    to be tap-ready. Empirically 1.5s on cold WhatsApp,
        //    700ms on warm — splitting the difference.
        delay(WAIT_FOR_CHAT_MS)

        // 3. Tap the send button. WhatsApp content-descriptions
        //    have shifted over versions — try the common ones
        //    in order. Fallback to "Send" as a substring match.
        val sent = service.tapNodeWithDesc("Send")
            || service.tapNodeWithDesc("send")
            || service.tapNodeWithId("send")
        if (!sent) {
            // Couldn't find the send button. Leave the user inside
            // WhatsApp with the message ready to tap; return a
            // structured error so the agent can tell the user.
            return ToolResult(
                ok = false,
                output = """{"error":"send_button_not_found","detail":"Opened WhatsApp with the message ready, but couldn't find the send button automatically. Tap Send manually."}""",
            )
        }

        // 4. Give WhatsApp a beat to register the send, then bring
        //    Mythara back to the foreground.
        delay(RETURN_DELAY_MS)
        service.bringMytharaToFront()

        return ToolResult(
            ok = true,
            output = """{"ok":true,"sent_to":${JsonPrimitive(to)},"body_len":${body.length},"returned_to_mythara":true}""",
        )
    }

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val PREVIEW_CHARS = 160
        /** Time given to WhatsApp to render the chat surface after the deep-link launch. */
        private const val WAIT_FOR_CHAT_MS = 1_200L
        /** Time between tapping Send and bringing Mythara back. */
        private const val RETURN_DELAY_MS = 600L
    }
}
