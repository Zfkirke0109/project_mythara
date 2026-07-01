package com.mythara.minimax

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.mythara.ai.AiProviderInterface
import com.mythara.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compatibility wrapper for the old Gemini vision path.
 *
 * The public name stays because SettingsViewModel and older call sites know it,
 * but the transport now goes through the same LiteLLM `/v1/chat/completions`
 * proxy contract as the rest of the app. No Gemini API key or Google endpoint
 * is stored in, or called directly from, the Android client.
 */
@Singleton
class GeminiVisionService @Inject constructor(
    private val settings: SettingsStore,
) {

    data class Outcome(val ok: Boolean, val text: String, val code: String? = null)

    suspend fun describeImage(
        imageFile: File,
        prompt: String,
        apiKey: String = "",
        model: String = DEFAULT_MODEL,
    ): Outcome = withContext(Dispatchers.IO) {
        if (!imageFile.exists() || imageFile.length() == 0L) {
            return@withContext Outcome(false, "Image file missing or empty.", "no_image")
        }
        val bytes = runCatching { downsampleToJpeg(imageFile) }.getOrElse {
            return@withContext Outcome(false, "Couldn't decode image: ${it.message}", "decode_failed")
        } ?: return@withContext Outcome(false, "Couldn't decode image (null bitmap).", "decode_failed")
        if (bytes.size > MAX_BYTES) {
            return@withContext Outcome(
                false,
                "Image too large after downsample (${bytes.size} bytes), capped at $MAX_BYTES.",
                "image_too_large",
            )
        }

        val snap = settings.snapshot()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val request = AiProviderInterface.visionChatRequest(
            prompt = prompt,
            jpegBase64 = b64,
            model = model,
            maxTokens = MAX_RESPONSE_TOKENS,
        )
        val client = MiniMaxClient(
            apiKey = apiKey.ifBlank { snap.apiKey.orEmpty() },
            region = snap.region,
            proxyBaseUrl = snap.aiProxyUrl,
        )
        val result = runCatching { client.retrofit.chatCompletionNonStreaming(request) }
        if (result.isFailure) {
            val e = result.exceptionOrNull()
            Log.w(TAG, "LiteLLM vision call threw", e)
            return@withContext Outcome(false, e?.message ?: "network failure", "network")
        }
        val res = result.getOrThrow()
        if (!res.isSuccessful) {
            val mapped = ErrorMapper.fromHttp(res.code(), res.errorBody()?.string())
            Log.w(TAG, "LiteLLM vision ${res.code()}: ${mapped.message}")
            return@withContext Outcome(false, mapped.message, mapped.code ?: "http_${res.code()}")
        }
        val text = res.body()?.choices?.firstOrNull()?.message?.content?.trim()
        if (text.isNullOrBlank()) {
            return@withContext Outcome(false, "Empty response.", "empty")
        }
        Outcome(ok = true, text = text)
    }

    suspend fun validate(apiKey: String = "", model: String = DEFAULT_MODEL): Outcome =
        withContext(Dispatchers.IO) {
            val snap = settings.snapshot()
            val client = MiniMaxClient(
                apiKey = apiKey.ifBlank { snap.apiKey.orEmpty() },
                region = snap.region,
                proxyBaseUrl = snap.aiProxyUrl,
            )
            val models = runCatching { client.validateKey().getOrThrow() }
            if (models.isSuccess) {
                return@withContext Outcome(
                    true,
                    "proxy OK - ${models.getOrNull()?.size ?: 0} models visible",
                )
            }
            val e = models.exceptionOrNull()
            Outcome(false, e?.message ?: "proxy validation failed", "validation_failed")
        }

    companion object {
        private const val TAG = "Mythara/ProxyVision"
        val DEFAULT_MODEL: String = AiProviderInterface.DEFAULT_VISION_MODEL

        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_RESPONSE_TOKENS = 256
        private const val MAX_LONG_EDGE_PX = 1024
        private const val JPEG_QUALITY = 85
    }

    private fun downsampleToJpeg(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val srcLong = maxOf(bounds.outWidth, bounds.outHeight)
        if (srcLong <= 0) return null
        var sample = 1
        while (srcLong / sample > MAX_LONG_EDGE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }
}
