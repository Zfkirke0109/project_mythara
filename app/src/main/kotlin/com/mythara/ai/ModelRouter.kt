package com.mythara.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.mythara.agent.Thinks
import com.mythara.data.SettingsStore
import com.mythara.minimax.MiniMaxClient
import com.mythara.minimax.models.ChatMessage
import com.mythara.minimax.models.ChatRequest
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes on-device insight generation between the **local Gemma** model
 * and the **LiteLLM proxy** cloud model, with a task-based policy:
 *
 *  - **Light** work (short summaries, day digests) → always local Gemma.
 *    Cheap, fast, fully offline; cloud quality wouldn't move the needle.
 *  - **Heavy** synthesis (Big Five, persona, the relationship graph,
 *    key-points) → LiteLLM proxy when the user has configured an endpoint AND
 *    the network is up — that's the better model for nuanced reads.
 *    Falls back to local Gemma when offline, unconfigured, or on any
 *    cloud error, so insights still generate either way.
 *
 * This keeps the main chat agent (already on MiniMax via [com.mythara
 * .agent.AgentLoop]) and the insight builders on a consistent model
 * story, while keeping per-call cost down — only the genuinely heavy
 * synthesis pays for cloud tokens.
 *
 * The API surface mirrors the slice of [GemmaExtractor] the insight
 * builders use, so wiring a builder through the router is a near
 * mechanical swap.
 */
@Singleton
class ModelRouter @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val gemma: GemmaExtractor,
    private val settings: SettingsStore,
) {

    /**
     * One-shot text completion. [heavy] = true routes to MiniMax first
     * (when available), falling back to local Gemma; [heavy] = false
     * goes straight to local Gemma. [maxLen] caps the returned text
     * length, matching [GemmaExtractor.runRaw]'s contract.
     */
    suspend fun runRaw(prompt: String, maxLen: Int, heavy: Boolean): String? {
        if (heavy) {
            val cloud = runCatching { cloudComplete(prompt, maxLen) }.getOrNull()
            if (!cloud.isNullOrBlank()) return cloud
        }
        return runCatching { gemma.runRaw(prompt, maxLen) }.getOrNull()
    }

    /**
     * Summarise [text]. Light by default — relationship summaries and
     * day digests stay local. [heavy] = true is available for symmetry
     * (routes a "summarise this" prompt to the cloud first).
     */
    suspend fun summarise(text: String, maxLen: Int, heavy: Boolean = false): String? {
        if (heavy) {
            val cloud = runCatching {
                cloudComplete("Summarise the following clearly and concisely.\n\n$text", maxLen)
            }.getOrNull()
            if (!cloud.isNullOrBlank()) return cloud
        }
        return runCatching { gemma.summarise(text, maxLen) }.getOrNull()
    }

    /**
     * True when *some* model can run an inference right now — either the
     * local Gemma engine is loaded, or the LiteLLM proxy is configured with
     * a live network. Insight builders gate their Gemma pass on this.
     */
    suspend fun canInfer(): Boolean = gemma.isReady() || cloudConfigured()

    /** True when a proxy endpoint is configured and the device has a live network. */
    suspend fun cloudConfigured(): Boolean {
        val snap = runCatching { settings.snapshot() }.getOrNull() ?: return false
        return snap.aiProxyUrl.isNotBlank() && hasNetwork()
    }

    // ----------------------------------------------------------- cloud

    private suspend fun cloudComplete(prompt: String, maxLen: Int): String? =
        withContext(Dispatchers.IO) {
            val snap = runCatching { settings.snapshot() }.getOrNull() ?: return@withContext null
            if (!hasNetwork()) return@withContext null

            val client = MiniMaxClient(
                apiKey = snap.apiKey,
                region = snap.region,
                proxyBaseUrl = snap.aiProxyUrl,
            )
            val req = ChatRequest(
                model = snap.model,
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                stream = false,
                temperature = 0.4,
                // maxLen is a CHAR cap; ~2 chars/token here is a safe
                // over-estimate so the model never truncates the answer.
                maxCompletionTokens = (maxLen / 2).coerceIn(200, 1600),
            )
            val resp = runCatching { client.retrofit.chatCompletionText(req) }.getOrElse { e ->
                Log.w(TAG, "cloud completion threw: ${e.message}")
                com.mythara.ui.system.ApiStatusStore.markMinimaxError()
                return@withContext null
            }
            if (!resp.isSuccessful) {
                Log.w(TAG, "cloud completion http ${resp.code()} — falling back to local")
                com.mythara.ui.system.ApiStatusStore.markMinimaxError()
                return@withContext null
            }
            // 2xx — flag MiniMax healthy so the status-bar dot
            // glows blue. Decay logic in ApiStatusStore moves the
            // dot back from red to blue automatically once 90 s
            // pass without a fresh failure, but explicit on-success
            // marking gets us there faster.
            com.mythara.ui.system.ApiStatusStore.markMinimaxOnline()
            val content = resp.body()?.choices?.firstOrNull()?.message?.content
                ?: return@withContext null
            // MiniMax M2 can embed <think> reasoning in content — strip it,
            // then cap to maxLen so callers' length contract still holds.
            Thinks.strip(content).trim().take(maxLen).ifBlank { null }
        }

    private fun hasNetwork(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val TAG = "Mythara/ModelRouter"
    }
}
