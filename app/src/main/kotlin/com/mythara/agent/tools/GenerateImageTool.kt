package com.mythara.agent.tools

import android.content.Context
import android.util.Base64
import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.data.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `generate_image` — generate an image from a text prompt via Gemini.
 *
 * Uses Gemini's native image generation (`gemini-2.5-flash-image`,
 * formerly "Nano Banana") through the standard `:generateContent`
 * endpoint with `responseModalities: ["IMAGE"]`. Image bytes come
 * back inline (base64) in the response, so there's NO second
 * download step.
 *
 * MiniMax's image-gen path was previously a fallback but: (1) its
 * tier-gated availability meant production failures with cryptic
 * `download_failed` errors on expired signed CDN URLs, and (2) the
 * user explicitly wants Gemini-only routing. Image generation
 * therefore returns a structured error when no Gemini key is set
 * instead of trying anything else.
 *
 * Output: image saved to `filesDir/canvas/images/<uuid>.<ext>`
 * (typically `.png`); absolute path returned in the tool result.
 * The agent passes that path into [RenderCanvasTool] via an
 * `<img src="file://…">` to display it.
 */
@Singleton
class GenerateImageTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) : Tool {
    override val name = "generate_image"
    override val description =
        "Generate an image from a text prompt via Gemini ($GEMINI_IMAGE_MODEL). " +
            "Returns a local file path the canvas can display via " +
            "`<img src=\"file://…\">`. Requires a Gemini API key in Settings."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("prompt", buildJsonObject {
                put("type", "string")
                put("description", "What to generate. Concrete + visual; e.g. 'sunset over a forest, painterly'.")
            })
            put("style", buildJsonObject {
                put("type", "string")
                put("description", "Optional style hint (e.g. 'photoreal', 'watercolor', 'low-poly'). Folded into prompt.")
            })
            put("aspect", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "Optional aspect hint folded into the prompt as text (Gemini doesn't take " +
                        "a structured aspect parameter — phrasing is the lever). Values like " +
                        "'square', 'landscape', 'portrait', '16:9', '9:16'.",
                )
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("prompt"))))
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: JsonObject): ToolResult {
        val rawPrompt = args["prompt"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (rawPrompt.isBlank()) return ToolResult.fail("prompt must be non-empty")
        val style = args["style"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val aspect = args["aspect"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        // Gemini doesn't take a structured aspect parameter — bake
        // both style and aspect hints into the natural-language prompt.
        val prompt = buildString {
            append(rawPrompt)
            if (style.isNotBlank()) append(", $style")
            if (aspect.isNotBlank()) append(", $aspect")
        }

        val geminiKey = settings.geminiKeyFlow().first().orEmpty()
        if (geminiKey.isBlank()) {
            return ToolResult.fail(
                "image_gen_no_key: image generation requires a Gemini API key. " +
                    "Open Settings → paste your Gemini API key, then retry.",
            )
        }

        return withContext(Dispatchers.IO) {
            runCatching { generateViaGemini(prompt, geminiKey) }
                .getOrElse { e ->
                    Log.w(TAG, "Gemini image-gen threw: ${e.message}", e)
                    ToolResult.fail(
                        "image_gen_error: ${e.message ?: e.javaClass.simpleName}. " +
                            "Check your Gemini API key + quota.",
                    )
                }
                ?: ToolResult.fail(
                    "image_gen_failed: Gemini returned no image for the prompt. " +
                        "Try rephrasing — Gemini's safety filters can reject otherwise-fine prompts.",
                )
        }
    }

    // ─── Gemini path ─────────────────────────────────────────────────
    // POST https://generativelanguage.googleapis.com/v1beta/models/
    //   gemini-2.5-flash-image-preview:generateContent?key=<KEY>
    // body: { contents:[{parts:[{text:"<prompt>"}]}],
    //         generationConfig:{responseModalities:["IMAGE"]} }
    // response contains parts with inlineData{mimeType,data:base64}.
    private fun generateViaGemini(prompt: String, apiKey: String): ToolResult? {
        val bodyJson = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("IMAGE"))
                })
            })
        }
        val body = bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "$GEMINI_IMAGE_MODEL:generateContent?key=$apiKey"
        val req = Request.Builder().url(url).post(body)
            .header("Content-Type", "application/json")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Gemini image-gen http ${resp.code}: ${resp.body?.string()?.take(300)}")
                return null
            }
            val text = resp.body?.string().orEmpty()
            val (bytes, mime) = parseGeminiInlineImage(text) ?: run {
                Log.w(TAG, "Gemini image-gen: no inlineData in response (${text.take(200)})")
                return null
            }
            val file = saveBytes(bytes, mimeToExt(mime))
            return ToolResult.ok(
                """{"path":"${file.absolutePath.escape()}","backend":"gemini","model":"$GEMINI_IMAGE_MODEL","prompt":"${prompt.escape()}"}""",
            )
        }
    }

    private fun parseGeminiInlineImage(json: String): Pair<ByteArray, String>? = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        val candidates = root["candidates"]?.jsonArray ?: return@runCatching null
        for (cand in candidates) {
            val parts = cand.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
                ?: continue
            for (part in parts) {
                val inline = part.jsonObject["inlineData"]?.jsonObject ?: continue
                val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull() ?: continue
                val b64 = inline["data"]?.jsonPrimitive?.contentOrNull() ?: continue
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                if (bytes.isNotEmpty()) return@runCatching bytes to mime
            }
        }
        null
    }.getOrNull()

    /** Write bytes to filesDir/canvas/images. */
    private fun saveBytes(bytes: ByteArray, ext: String): File {
        val dir = File(context.filesDir, "canvas/images").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.$ext")
        file.writeBytes(bytes)
        return file
    }

    private fun mimeToExt(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = JSONObject.quote(this).removeSurrounding("\"")

    companion object {
        private const val TAG = "Mythara/ImageGen"
        /** Gemini's native image-generation model. Formerly known as
         *  "Nano Banana" while in preview as `gemini-2.5-flash-image
         *  -preview`; now in production as `gemini-2.5-flash-image`.
         *  Returns PNG bytes inline in the `:generateContent`
         *  response under the same Gemini API key used for vision
         *  captioning. No second download hop. */
        const val GEMINI_IMAGE_MODEL = "gemini-2.5-flash-image"
    }
}
