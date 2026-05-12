package com.mythara.ui.secret

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.secret.SecretAuthStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the secret-mode unlock dialog. Two modes:
 *  - first-time: no password is set → user picks one + confirms it
 *  - returning : password exists → verify a single field
 *
 * Cooldown / lockout from too many wrong attempts is enforced by
 * [SecretAuthStore]; we surface it as a UI state.
 */
@HiltViewModel
class SecretViewModel @Inject constructor(
    private val store: SecretAuthStore,
) : ViewModel() {

    data class State(
        val isSetupMode: Boolean = false,
        val checking: Boolean = false,
        val error: String? = null,
        val unlocked: Boolean = false,
        val cooldownRemainingMs: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun probe() {
        viewModelScope.launch {
            val isSetup = !store.hasPassword()
            _state.update { it.copy(isSetupMode = isSetup, error = null) }
        }
    }

    fun setup(password: String, confirm: String) {
        if (password.length < SecretAuthStore.MIN_LENGTH) {
            _state.update { it.copy(error = "password must be at least ${SecretAuthStore.MIN_LENGTH} characters") }
            return
        }
        if (password != confirm) {
            _state.update { it.copy(error = "passwords don't match") }
            return
        }
        _state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            runCatching { store.setPassword(password) }
                .onSuccess { _state.update { it.copy(checking = false, unlocked = true, isSetupMode = false, error = null) } }
                .onFailure { e -> _state.update { it.copy(checking = false, error = e.message) } }
        }
    }

    fun verify(password: String) {
        _state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            when (val r = store.verify(password)) {
                SecretAuthStore.VerifyResult.Ok -> _state.update {
                    it.copy(checking = false, unlocked = true, error = null, cooldownRemainingMs = 0)
                }
                SecretAuthStore.VerifyResult.Wrong -> _state.update {
                    it.copy(checking = false, error = "wrong password")
                }
                SecretAuthStore.VerifyResult.Unset -> _state.update {
                    it.copy(checking = false, isSetupMode = true, error = null)
                }
                is SecretAuthStore.VerifyResult.Cooldown -> {
                    val mins = (r.millisRemaining / 60_000).coerceAtLeast(1)
                    _state.update {
                        it.copy(
                            checking = false,
                            error = "too many wrong attempts — try again in $mins min",
                            cooldownRemainingMs = r.millisRemaining,
                        )
                    }
                }
            }
        }
    }

    fun reset() {
        _state.update { State() }
    }
}
