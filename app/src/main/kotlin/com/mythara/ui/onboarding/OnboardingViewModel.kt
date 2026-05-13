package com.mythara.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.OnboardingStore
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.extract.gemma.GemmaModelStore
import com.mythara.secret.observe.vosk.VoskModelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backing VM for [OnboardingScreen]. Holds three concerns:
 *
 *  1. Exposes the three lazy-download model stores' state flows so
 *     the UI can render progress bars.
 *  2. Provides "kick off" + "skip" entry points the user taps.
 *  3. Marks the onboarding-completed flag in [OnboardingStore] when
 *     the user hits "I'm done" — flipping that flag is what causes
 *     [com.mythara.ui.MytharaRoot] to pivot out of onboarding into
 *     the AuthGate.
 *
 * Model downloads are explicitly opt-in:
 *   - USE-Lite embedder (~6MB) — used by chat recall + analytics
 *   - Vosk small EN (~50MB) — used by wake-word + Observe ASR
 *   - Gemma 4 E2B (~2.6GB) — used by Observe learning extraction
 *
 * The first two are small enough to recommend downloading during
 * onboarding; Gemma is big enough that we surface a "skip for now —
 * download later from Secret settings" affordance.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingStore: OnboardingStore,
    private val embeddingsStore: EmbeddingsModelStore,
    private val voskStore: VoskModelStore,
    private val gemmaStore: GemmaModelStore,
) : ViewModel() {

    val embeddingsState: StateFlow<EmbeddingsModelStore.State> = embeddingsStore.state
    val voskState: StateFlow<VoskModelStore.State> = voskStore.activeState
    val gemmaState: StateFlow<GemmaModelStore.State> = gemmaStore.state

    fun downloadEmbedder() {
        viewModelScope.launch { embeddingsStore.ensureReady() }
    }

    fun downloadVosk() {
        viewModelScope.launch { voskStore.ensureReady() }
    }

    fun downloadGemma() {
        viewModelScope.launch { gemmaStore.ensureReady() }
    }

    fun complete() {
        viewModelScope.launch { onboardingStore.markCompleted() }
    }
}
