package com.mythara.minimax

import com.mythara.ai.AiProviderInterface
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory for LiteLLM/OpenAI-compatible network clients.
 *
 * The class/package names stay MiniMaxClient/com.mythara.minimax so the rest of
 * the app does not need import rewrites. Only the endpoint/auth behavior changes.
 */
class MiniMaxClient(
    private val apiKey: String?,
    private val region: Region,
    proxyBaseUrl: String = region.baseUrl,
) {
    val baseUrl: String = AiProviderInterface.normalizeProxyBaseUrl(proxyBaseUrl)

    private val authInterceptor = Interceptor { chain ->
        val reqBuilder = chain.request().newBuilder()
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        AiProviderInterface.authorizationHeader(apiKey)?.let { auth ->
            reqBuilder.header("Authorization", auth)
        }

        chain.proceed(reqBuilder.build())
    }

    private val logging = HttpLoggingInterceptor().apply {
        // BODY can leak prompts/responses into logcat. BASIC is safer.
        level = HttpLoggingInterceptor.Level.BASIC
        redactHeader("Authorization")
        redactHeader("x-goog-api-key")
    }

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val retrofit: MiniMaxApi by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MiniMaxApi::class.java)
    }

    /**
     * Validates the configured LiteLLM proxy by calling its OpenAI-compatible
     * models endpoint.
     */
    suspend fun validateKey(): Result<List<String>> = runCatching {
        val res = retrofit.listModels()
        if (res.isSuccessful) {
            res.body()?.data?.map { it.id }.orEmpty()
        } else {
            val mapped = ErrorMapper.fromHttp(res.code(), res.errorBody()?.string())
            throw ApiException(mapped)
        }
    }

    companion object {
        val GEMINI_MODEL: String = AiProviderInterface.DEFAULT_CHAT_MODEL

        /**
         * Shared JSON config for OpenAI-compatible request/response bodies.
         */
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
            explicitNulls = false
        }
    }
}

class ApiException(val mapped: ErrorMapper.Mapped) :
    RuntimeException("AI proxy ${mapped.httpStatus} ${mapped.code ?: ""}: ${mapped.message}")
