package com.mythara.ai

import com.mythara.minimax.models.VisionChatRequest
import com.mythara.minimax.models.VisionContentPart
import com.mythara.minimax.models.VisionImageUrl
import com.mythara.minimax.models.VisionMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Owns Mythara's cloud-model wire contract.
 *
 * The app speaks standard OpenAI-compatible LiteLLM proxy routes only:
 * `/v1/models`, `/v1/chat/completions`, and `/v1/images/generations`.
 * Provider credentials stay behind the proxy (Termux, Vercel, or any
 * reachable LiteLLM deployment); the Android client stores only the proxy
 * URL and an optional LiteLLM virtual key.
 */
object AiProviderInterface {
    const val DEFAULT_PROXY_ORIGIN = "http://127.0.0.1:4000"
    const val DEFAULT_PROXY_BASE_URL = "http://127.0.0.1:4000/v1/"
    const val DEFAULT_CHAT_MODEL = "gemini/gemini-1.5-pro"
    const val DEFAULT_VISION_MODEL = DEFAULT_CHAT_MODEL
    const val DEFAULT_IMAGE_MODEL = "gemini/gemini-2.5-flash-image"

    val SUPPORTED_CHAT_MODELS: List<String> = listOf(
        "gemini/gemini-1.5-pro",
        "gemini/gemini-2.5-flash",
        "openai/gpt-4o-mini",
        "openai/gpt-4o",
    )

    fun normalizeProxyBaseUrl(raw: String?): String {
        val trimmed = raw?.trim()?.ifBlank { null } ?: DEFAULT_PROXY_ORIGIN
        val noTrailingSlash = trimmed.trimEnd('/')
        val withV1 = if (noTrailingSlash.endsWith("/v1")) {
            noTrailingSlash
        } else {
            "$noTrailingSlash/v1"
        }
        return "$withV1/"
    }

    fun chatCompletionsUrl(raw: String?): String =
        normalizeProxyBaseUrl(raw) + "chat/completions"

    fun imageGenerationsUrl(raw: String?): String =
        normalizeProxyBaseUrl(raw) + "images/generations"

    fun authorizationHeader(proxyKey: String?): String? {
        val trimmed = proxyKey?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (trimmed.startsWith("Bearer ", ignoreCase = true)) trimmed else "Bearer $trimmed"
    }

    fun coerceModel(model: String?): String {
        val trimmed = model?.trim().orEmpty()
        return trimmed.ifBlank { DEFAULT_CHAT_MODEL }
    }

    fun visionChatRequest(
        prompt: String,
        jpegBase64: String,
        model: String = DEFAULT_VISION_MODEL,
        maxTokens: Int = 256,
    ): VisionChatRequest = VisionChatRequest(
        model = coerceModel(model),
        stream = false,
        messages = listOf(
            VisionMessage(
                role = "user",
                content = listOf(
                    VisionContentPart(type = "text", text = prompt),
                    VisionContentPart(
                        type = "image_url",
                        imageUrl = VisionImageUrl(
                            url = "data:image/jpeg;base64,$jpegBase64",
                            detail = "auto",
                        ),
                    ),
                ),
            ),
        ),
        temperature = 0.4,
        maxCompletionTokens = maxTokens,
    )

    fun imageGenerationRequest(
        prompt: String,
        model: String = DEFAULT_IMAGE_MODEL,
        size: String = "1024x1024",
    ): ImageGenerationRequest = ImageGenerationRequest(
        model = model,
        prompt = prompt,
        size = size,
        responseFormat = "b64_json",
    )

    @Serializable
    data class ImageGenerationRequest(
        val model: String,
        val prompt: String,
        val size: String? = null,
        @SerialName("response_format") val responseFormat: String? = null,
    )

    @Serializable
    data class ImageGenerationResponse(
        val data: List<ImageData> = emptyList(),
        val error: ProxyError? = null,
    )

    @Serializable
    data class ImageData(
        @SerialName("b64_json") val b64Json: String? = null,
        val url: String? = null,
        @SerialName("revised_prompt") val revisedPrompt: String? = null,
    )

    @Serializable
    data class ProxyError(
        val message: String? = null,
        val type: String? = null,
        val code: String? = null,
    )
}
