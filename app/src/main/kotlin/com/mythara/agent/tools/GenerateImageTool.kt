package com.mythara.agent.tools

import android.content.Context
import android.util.Base64
import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.ai.AiProviderInterface
import com.mythara.data.SettingsStore
import com.mythara.minimax.MiniMaxClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
 * `generate_image` — generate an image from a text prompt via LiteLLM.
 *
 * Uses the configured LiteLLM proxy's OpenAI-compatible
 * `/v1/images/generations` route. Provider API keys stay behind the
 * proxy; the app stores only the endpoint and optional LiteLLM virtual key.
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
        "Generate an image from a text prompt via the configured LiteLLM proxy ($IMAGE_MODEL). " +
            "Returns a local file path the canvas can display via " +
            "`<img src=\"file://…\">`."

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

        val snap = settings.snapshot()

        return withContext(Dispatchers.IO) {
            runCatching { generateViaProxy(prompt, snap) }
                .getOrElse { e ->
                    Log.w(TAG, "LiteLLM image-gen threw: ${e.message}", e)
                    ToolResult.fail(
                        "image_gen_error: ${e.message ?: e.javaClass.simpleName}. " +
                            "Check your LiteLLM proxy URL, virtual key, and provider quota.",
                    )
                }
                ?: ToolResult.fail(
                    "image_gen_failed: the LiteLLM proxy returned no inline image for the prompt.",
                )
        }
    }

    private fun generateViaProxy(
        prompt: String,
        snap: SettingsStore.Snapshot,
    ): ToolResult? {
        val bodyJson = MiniMaxClient.json.encodeToString(
            AiProviderInterface.ImageGenerationRequest.serializer(),
            AiProviderInterface.imageGenerationRequest(prompt = prompt, model = IMAGE_MODEL),
        )
        val body = bodyJson.toRequestBody("application/json".toMediaTypeOrNull())
        val builder = Request.Builder()
            .url(AiProviderInterface.imageGenerationsUrl(snap.aiProxyUrl))
            .post(body)
            .header("Content-Type", "application/json")
        AiProviderInterface.authorizationHeader(snap.apiKey)?.let { builder.header("Authorization", it) }
        val req = builder.build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "LiteLLM image-gen http ${resp.code}: ${resp.body?.string()?.take(300)}")
                return null
            }
            val text = resp.body?.string().orEmpty()
            val image = runCatching {
                MiniMaxClient.json.decodeFromString(
                    AiProviderInterface.ImageGenerationResponse.serializer(),
                    text,
                )
            }.getOrNull()?.data?.firstOrNull()
            val b64 = image?.b64Json
            if (b64.isNullOrBlank()) {
                Log.w(TAG, "LiteLLM image-gen: no b64_json in response (${text.take(200)})")
                return null
            }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val file = saveBytes(bytes, "png")
            return ToolResult.ok(
                """{"path":"${file.absolutePath.escape()}","backend":"litellm-proxy","model":"$IMAGE_MODEL","prompt":"${prompt.escape()}"}""",
            )
        }
    }

    /** Write bytes to filesDir/canvas/images. */
    private fun saveBytes(bytes: ByteArray, ext: String): File {
        val dir = File(context.filesDir, "canvas/images").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.$ext")
        file.writeBytes(bytes)
        return file
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = JSONObject.quote(this).removeSurrounding("\"")

    companion object {
        private const val TAG = "Mythara/ImageGen"
        val IMAGE_MODEL: String = AiProviderInterface.DEFAULT_IMAGE_MODEL
    }
}
