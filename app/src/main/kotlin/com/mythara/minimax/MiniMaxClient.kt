package com.mythara.minimax

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory for Gemini OpenAI-compatible network clients.
 *
 * The class/package names stay MiniMaxClient/com.mythara.minimax so the rest of
 * the app does not need import rewrites. Only the endpoint/auth behavior changes.
 */
class MiniMaxClient(
    private val apiKey: String,
    private val region: Region,
) {
    private val authInterceptor = Interceptor { chain ->
        val trimmedKey = apiKey.trim()

        val req = chain.request().newBuilder()
            // Gemini OpenAI-compatible endpoints use OpenAI-style Bearer auth.
            // Keep the key in SettingsStore/DataStore; do not hardcode it here.
            .header("Authorization", "Bearer $trimmedKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()

        chain.proceed(req)
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
            // readTimeout(0) means "no read timeout" and is required for long SSE streams.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val retrofit: MiniMaxApi by lazy {
        Retrofit.Builder()
            .baseUrl(region.baseUrl)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MiniMaxApi::class.java)
    }

    /**
     * Validates the configured Gemini API key by calling the OpenAI-compatible
     * models endpoint:
     *
     *   GET https://generativelanguage.googleapis.com/v1beta/openai/models
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
        const val GEMINI_MODEL: String = "gemini-3.5-flash"

        /**
         * Shared JSON config for OpenAI-compatible Gemini request/response bodies.
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
    RuntimeException("Gemini ${mapped.httpStatus} ${mapped.code ?: ""}: ${mapped.message}")
