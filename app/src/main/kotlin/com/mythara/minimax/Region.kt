package com.mythara.minimax

import com.mythara.ai.AiProviderInterface

/**
 * Kept as Region for compatibility with existing SettingsStore/UI code.
 * Endpoint selection now resolves through SettingsStore.aiProxyUrl; these
 * values are only safe LiteLLM defaults for older call sites.
 */
enum class Region(val label: String, val baseUrl: String) {
    Global("LiteLLM proxy", AiProviderInterface.DEFAULT_PROXY_BASE_URL),
    China("LiteLLM proxy", AiProviderInterface.DEFAULT_PROXY_BASE_URL);

    companion object {
        val Default: Region = Global
        fun fromId(id: String?): Region = entries.firstOrNull { it.name == id } ?: Default
    }
}
