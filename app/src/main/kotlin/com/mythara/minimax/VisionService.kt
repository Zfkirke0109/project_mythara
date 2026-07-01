package com.mythara.minimax

import android.util.Base64
import android.util.Log
import com.mythara.ai.AiProviderInterface
import com.mythara.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot vision queries through the configured LiteLLM proxy.
 *
 * Used by the `take_photo` agent tool: after CameraX saves the JPEG,
 * this service base64-encodes it inline and asks the VL model what's
 * in the image. The response comes back synchronously as text — the
 * tool returns it to the agent loop as part of its result, so the
 * model on the next iteration "sees" the photo via the captured text
 * description, without any multimodal plumbing in the streaming path.
 *
 * Why MiniMax-VL-01 specifically (and not just the user's selected
 * model): the user picks M2/M2.5/M2.7 for text reasoning + tool
 * use, but those models aren't vision-capable. Routing vision calls
 * to VL-01 transparently keeps the user's text-model choice
 * undisturbed while still giving the assistant eyes.
 *
 * Non-streaming. SSE doesn't buy us anything here — the description
 * is short, and the tool result needs the full text before it can be
 * returned anyway. Single `POST /chat/completions` with `stream=false`.
 *
 * Privacy: the image bytes leave the device as inline base64 in the
 * request body. There's no Mythara backend; this goes straight to
 * the configured LiteLLM proxy. The saved JPEG file stays in private
 * filesDir/photos/ regardless.
 */
@Singleton
class VisionService @Inject constructor(
    private val settings: SettingsStore,
    private val gemmaVision: GemmaVisionService,
) {
    /**
     * Successful path: free-text description from whichever vision
     * model handled the call. [backend] reports which one was used so
     * the tool result and any debug surface can attribute it.
     */
    data class Outcome(
        val ok: Boolean,
        val text: String,
        val code: String? = null,
        /** "gemma-on-device" | "ai-proxy-vision" | null on early failure */
        val backend: String? = null,
    )

    /**
     * Send `imageFile` plus a textual `prompt` to MiniMax-VL-01.
     * Returns an [Outcome] — never throws. Caller decides whether to
     * surface the error to the model.
     *
     * @param prompt  Short instruction. Defaults to a generic "describe
     *                what's in this photo" — callers should pass a more
     *                specific prompt when the user's intent is known
     *                (e.g. "is the person in this picture wearing a
     *                helmet?").
     */
    suspend fun describeImage(
        imageFile: File,
        prompt: String = DEFAULT_PROMPT,
    ): Outcome = withContext(Dispatchers.IO) {
        if (!imageFile.exists() || imageFile.length() == 0L) {
            return@withContext Outcome(false, "Image file is missing or empty.", code = "no_image")
        }
        val snap = settings.snapshot()

        // ── Routing ────────────────────────────────────────────────
        // Default order — on-device Gemma 4 E2B → LiteLLM proxy.
        // When the user flips `preferCloudVision` in
        // Settings, the first two swap (proxy cloud → Gemma)
        // — useful when the user has a proxy-backed vision model and wants the
        // higher-accuracy cloud caption per call.
        val gemmaFn: suspend () -> Outcome? = { tryGemmaOnDevice(imageFile, prompt) }
        val proxyFn: suspend () -> Outcome? = { tryLiteLlmVision(snap, imageFile, prompt) }
        val ordered: List<suspend () -> Outcome?> = if (snap.preferCloudVision) {
            listOf(proxyFn, gemmaFn)
        } else {
            listOf(gemmaFn, proxyFn)
        }
        for (fn in ordered) {
            val r = fn() ?: continue
            // tryX returns null when the path is unavailable
            // (model not loaded / no key) so we can cascade. A
            // non-null Outcome — whether successful or a hard
            // failure — is what that route actually produced.
            // We only return on success; a non-ok result from a
            // cloud path is logged and we still cascade so a
            // transient cloud outage doesn't dead-end captioning.
            if (r.ok && r.text.isNotBlank()) {
                return@withContext r
            }
            Log.i(TAG, "vision backend ${r.backend ?: "?"} fell through (${r.code ?: "?"})")
        }
        Outcome(
            false,
            "No vision backend produced a result. " +
                "Install the Gemma model (Settings → on-device model) " +
                "or configure the LiteLLM proxy endpoint.",
            code = "all_backends_failed",
        )
    }

    /** Run the on-device Gemma 4 E2B path. Returns null when the
     *  model isn't loaded (so the caller cascades) or the populated
     *  Outcome when it ran. */
    private suspend fun tryGemmaOnDevice(imageFile: File, prompt: String): Outcome? {
        if (!gemmaVision.isAvailable()) return null
        val r = runCatching { gemmaVision.describeImage(imageFile, prompt) }
            .getOrElse { e ->
                GemmaVisionService.Outcome(false, e.message ?: "threw", code = "threw")
            }
        return Outcome(
            ok = r.ok,
            text = r.text,
            code = r.code,
            backend = "gemma-on-device",
        )
    }

    /** Run the cloud vision path through LiteLLM. */
    private suspend fun tryLiteLlmVision(
        snap: SettingsStore.Snapshot,
        imageFile: File,
        prompt: String,
    ): Outcome? {
        val bytes = runCatching { imageFile.readBytes() }.getOrElse {
            return Outcome(false, "Couldn't read image bytes: ${it.message}", code = "read_failed", backend = "ai-proxy-vision")
        }
        if (bytes.size > MAX_BYTES) {
            return Outcome(
                false,
                "Image is too large (${bytes.size} bytes) — vision is capped at $MAX_BYTES.",
                code = "image_too_large",
                backend = "ai-proxy-vision",
            )
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val request = AiProviderInterface.visionChatRequest(
            prompt = prompt,
            jpegBase64 = b64,
            model = VISION_MODEL,
            maxTokens = MAX_RESPONSE_TOKENS,
        )
        val client = MiniMaxClient(
            apiKey = snap.apiKey,
            region = snap.region,
            proxyBaseUrl = snap.aiProxyUrl,
        )
        val result = runCatching { client.retrofit.chatCompletionNonStreaming(request) }
        if (result.isFailure) {
            val e = result.exceptionOrNull()
            Log.w(TAG, "vision call threw", e)
            return Outcome(false, e?.message ?: "network failure", code = "network", backend = "ai-proxy-vision")
        }
        val res = result.getOrThrow()
        if (!res.isSuccessful) {
            val mapped = ErrorMapper.fromHttp(res.code(), res.errorBody()?.string())
            Log.w(TAG, "vision call ${res.code()}: ${mapped.message}")
            return Outcome(false, mapped.message, code = mapped.code ?: "http_${res.code()}", backend = "ai-proxy-vision")
        }
        val text = res.body()?.choices?.firstOrNull()?.message?.content
        if (text.isNullOrBlank()) {
            return Outcome(false, "Empty response from vision model.", code = "empty", backend = "ai-proxy-vision")
        }
        return Outcome(ok = true, text = text.trim(), backend = "ai-proxy-vision")
    }

    companion object {
        private const val TAG = "Mythara/Vision"

        /**
         * LiteLLM model string for a vision-capable provider target.
         */
        val VISION_MODEL: String = AiProviderInterface.DEFAULT_VISION_MODEL

        const val DEFAULT_PROMPT =
            "Describe what's in this photo in 1-2 short, natural sentences. " +
                "Focus on the main subject. Don't speculate beyond what's visible."

        /** Pre-encoding cap. Roughly 4MB JPEG → ~5.3MB base64. */
        private const val MAX_BYTES = 4 * 1024 * 1024

        /** Don't let the model rant — 1-2 sentences fits inside ~200 tokens. */
        private const val MAX_RESPONSE_TOKENS = 256
    }
}
