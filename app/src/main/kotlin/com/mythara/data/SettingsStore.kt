package com.mythara.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.mythara.ai.AiProviderInterface
import com.mythara.minimax.Region
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted persistence for the user's cloud API key + chosen endpoint +
 * model. Uses Jetpack DataStore plus Google Tink AEAD with the wrapping
 * key in the Android Keystore.
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mythara_settings")

    private val keyApiKeyEncrypted = stringPreferencesKey("apiKey.encrypted")

    /**
     * Legacy MiniMax web-session storage. Kept so existing call sites still compile.
     */
    private val keyMiniMaxWebSessionEncrypted = stringPreferencesKey("miniMaxWebSession.encrypted")

    private val keyRegion = stringPreferencesKey("region")
    private val keyModel = stringPreferencesKey("model")
    private val keyAiProxyUrl = stringPreferencesKey("aiProxyUrl")

    /**
     * Legacy direct Gemini key storage. Kept only so existing installs can
     * decrypt/clear old values; active AI calls now use the LiteLLM proxy.
     */
    private val keyGeminiKeyEncrypted = stringPreferencesKey("geminiKey.encrypted")

    /**
     * ElevenLabs TTS key + settings.
     */
    private val keyElevenLabsKeyEncrypted = stringPreferencesKey("elevenLabsKey.encrypted")
    private val keyElevenLabsVoiceId = stringPreferencesKey("elevenLabsVoiceId")
    private val keyUseElevenLabs = booleanPreferencesKey("useElevenLabs")

    /**
     * Vision routing preference.
     */
    private val keyPreferCloudVision = booleanPreferencesKey("preferCloudVision")

    /**
     * Supertonic-2 voice name.
     */
    private val keySupertonicVoice = stringPreferencesKey("supertonicVoice")

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(ctx, "mythara_master_keyset", "mythara_master_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://mythara_master_key")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    fun apiKeyFlow(): Flow<String?> = ctx.dataStore.data.map { prefs ->
        prefs[keyApiKeyEncrypted]?.let { tryDecrypt(it) }
    }

    fun regionFlow(): Flow<Region> = ctx.dataStore.data.map { prefs ->
        Region.fromId(prefs[keyRegion])
    }

    fun modelFlow(): Flow<String> = ctx.dataStore.data.map { prefs ->
        coerceModel(prefs[keyModel])
    }

    fun aiProxyUrlFlow(): Flow<String> = ctx.dataStore.data.map { prefs ->
        AiProviderInterface.normalizeProxyBaseUrl(prefs[keyAiProxyUrl])
    }

    suspend fun setApiKey(plain: String) {
        val trimmed = plain.trim()
        if (trimmed.isBlank()) {
            ctx.dataStore.edit {
                it.remove(keyApiKeyEncrypted)
            }
            return
        }
        val ct = aead.encrypt(trimmed.toByteArray(Charsets.UTF_8), null)
        ctx.dataStore.edit {
            it[keyApiKeyEncrypted] = Base64.encodeToString(ct, Base64.NO_WRAP)
        }
    }

    /**
     * Stored legacy MiniMax web-session, decrypted. Returns null when unset,
     * expired, or undecryptable.
     */
    @kotlinx.serialization.Serializable
    data class MiniMaxWebSession(
        val token: String,
        val groupId: String,
        val expiresAtMs: Long,
    )

    private val webSessionJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun miniMaxWebSession(): MiniMaxWebSession? {
        val raw = ctx.dataStore.data.first()[keyMiniMaxWebSessionEncrypted] ?: return null
        val plain = tryDecrypt(raw) ?: return null
        return runCatching {
            webSessionJson.decodeFromString(MiniMaxWebSession.serializer(), plain)
        }.getOrNull()
    }

    suspend fun setMiniMaxWebSession(session: MiniMaxWebSession) {
        val plain = webSessionJson.encodeToString(MiniMaxWebSession.serializer(), session)
        val ct = aead.encrypt(plain.toByteArray(Charsets.UTF_8), null)
        ctx.dataStore.edit {
            it[keyMiniMaxWebSessionEncrypted] = Base64.encodeToString(ct, Base64.NO_WRAP)
        }
    }

    suspend fun clearMiniMaxWebSession() {
        ctx.dataStore.edit {
            it.remove(keyMiniMaxWebSessionEncrypted)
        }
    }

    fun geminiKeyFlow(): Flow<String?> = ctx.dataStore.data.map { prefs ->
        prefs[keyGeminiKeyEncrypted]?.let { tryDecrypt(it) }
    }

    suspend fun setGeminiKey(plain: String) {
        val trimmed = plain.trim()
        if (trimmed.isBlank()) {
            ctx.dataStore.edit {
                it.remove(keyGeminiKeyEncrypted)
            }
            return
        }

        val ct = aead.encrypt(trimmed.toByteArray(Charsets.UTF_8), null)
        ctx.dataStore.edit {
            it[keyGeminiKeyEncrypted] = Base64.encodeToString(ct, Base64.NO_WRAP)
        }
    }

    suspend fun clearGeminiKey() {
        ctx.dataStore.edit {
            it.remove(keyGeminiKeyEncrypted)
        }
    }

    // ---------- ElevenLabs ----------

    fun elevenLabsKeyFlow(): Flow<String?> = ctx.dataStore.data.map { prefs ->
        prefs[keyElevenLabsKeyEncrypted]?.let { tryDecrypt(it) }
    }

    fun elevenLabsVoiceIdFlow(): Flow<String> = ctx.dataStore.data.map { prefs ->
        prefs[keyElevenLabsVoiceId] ?: DEFAULT_ELEVEN_LABS_VOICE_ID
    }

    fun useElevenLabsFlow(): Flow<Boolean> = ctx.dataStore.data.map { prefs ->
        prefs[keyUseElevenLabs] ?: false
    }

    suspend fun setElevenLabsKey(plain: String) {
        val trimmed = plain.trim()
        if (trimmed.isBlank()) {
            ctx.dataStore.edit {
                it.remove(keyElevenLabsKeyEncrypted)
            }
            return
        }

        val ct = aead.encrypt(trimmed.toByteArray(Charsets.UTF_8), null)
        ctx.dataStore.edit {
            it[keyElevenLabsKeyEncrypted] = Base64.encodeToString(ct, Base64.NO_WRAP)
        }
    }

    suspend fun clearElevenLabsKey() {
        ctx.dataStore.edit {
            it.remove(keyElevenLabsKeyEncrypted)
        }
    }

    suspend fun setElevenLabsVoiceId(voiceId: String) {
        val v = voiceId.trim()
        ctx.dataStore.edit {
            if (v.isBlank()) {
                it.remove(keyElevenLabsVoiceId)
            } else {
                it[keyElevenLabsVoiceId] = v
            }
        }
    }

    suspend fun setUseElevenLabs(value: Boolean) {
        ctx.dataStore.edit {
            it[keyUseElevenLabs] = value
        }
    }

    suspend fun setPreferCloudVision(value: Boolean) {
        ctx.dataStore.edit {
            it[keyPreferCloudVision] = value
        }
    }

    suspend fun setSupertonicVoice(name: String) {
        val v = name.trim()
        ctx.dataStore.edit {
            if (v.isBlank()) {
                it.remove(keySupertonicVoice)
            } else {
                it[keySupertonicVoice] = v
            }
        }
    }

    suspend fun setRegion(region: Region) {
        ctx.dataStore.edit {
            it[keyRegion] = region.name
        }
    }

    suspend fun setModel(model: String) {
        ctx.dataStore.edit {
            it[keyModel] = coerceModel(model)
        }
    }

    suspend fun setAiProxyUrl(url: String) {
        val normalized = AiProviderInterface.normalizeProxyBaseUrl(url)
        ctx.dataStore.edit {
            it[keyAiProxyUrl] = normalized
        }
    }

    /**
     * Convenience snapshot for the network layer.
     */
    suspend fun snapshot(): Snapshot {
        val prefs = ctx.dataStore.data.first()
        return Snapshot(
            apiKey = prefs[keyApiKeyEncrypted]?.let { tryDecrypt(it) },
            region = Region.fromId(prefs[keyRegion]),
            aiProxyUrl = AiProviderInterface.normalizeProxyBaseUrl(prefs[keyAiProxyUrl]),
            model = coerceModel(prefs[keyModel]),
            geminiKey = prefs[keyGeminiKeyEncrypted]?.let { tryDecrypt(it) },
            elevenLabsKey = prefs[keyElevenLabsKeyEncrypted]?.let { tryDecrypt(it) },
            elevenLabsVoiceId = prefs[keyElevenLabsVoiceId] ?: DEFAULT_ELEVEN_LABS_VOICE_ID,
            useElevenLabs = prefs[keyUseElevenLabs] ?: false,
            preferCloudVision = prefs[keyPreferCloudVision] ?: false,
            supertonicVoice = prefs[keySupertonicVoice] ?: DEFAULT_SUPERTONIC_VOICE,
        )
    }

    private fun tryDecrypt(b64: String): String? = runCatching {
        val pt = aead.decrypt(Base64.decode(b64, Base64.NO_WRAP), null)
        String(pt, Charsets.UTF_8)
    }.getOrNull()

    data class Snapshot(
        val apiKey: String?,
        val region: Region,
        val aiProxyUrl: String = DEFAULT_AI_PROXY_URL,
        val model: String,
        val geminiKey: String? = null,
        val elevenLabsKey: String? = null,
        val elevenLabsVoiceId: String = DEFAULT_ELEVEN_LABS_VOICE_ID,
        val useElevenLabs: Boolean = false,
        val preferCloudVision: Boolean = false,
        val supertonicVoice: String = DEFAULT_SUPERTONIC_VOICE,
    )

    companion object {
        const val DEFAULT_AI_PROXY_URL: String = "http://127.0.0.1:4000/v1/"
        const val DEFAULT_MODEL: String = AiProviderInterface.DEFAULT_CHAT_MODEL

        val SUPPORTED_MODELS: List<String> = AiProviderInterface.SUPPORTED_CHAT_MODELS

        fun coerceModel(model: String?): String =
            AiProviderInterface.coerceModel(model)

        const val DEFAULT_ELEVEN_LABS_VOICE_ID: String = "21m00Tcm4TlvDq8ikWAM"

        const val DEFAULT_SUPERTONIC_VOICE: String = "M1"
    }
}
