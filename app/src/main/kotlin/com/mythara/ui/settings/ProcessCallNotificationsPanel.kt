package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.ProcessCallNotificationsStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProcessCallNotificationsViewModel @Inject constructor(
    private val store: ProcessCallNotificationsStore,
) : ViewModel() {
    val enabled: StateFlow<Boolean> =
        store.enabledFlow().stateIn(viewModelScope, SharingStarted.Eagerly, ProcessCallNotificationsStore.DEFAULT_ENABLED)

    fun set(value: Boolean) {
        viewModelScope.launch { store.setEnabled(value) }
    }
}

/**
 * Toggle: should the agent react to phone / VoIP call notifications?
 *
 * Default OFF. With OFF, call notifications (Notification.category ==
 * CATEGORY_CALL, known dialer packages, body patterns) never reach
 * any agent path — no triage, no auto-reply, no [notif] processing.
 *
 * Turning ON sends call notifications through the same auto-process
 * pipeline as messages. Most users don't want this — calls are a
 * separate interaction mode — but power users may want narration of
 * "X is calling" via TTS.
 */
@Composable
fun ProcessCallNotificationsPanel(vm: ProcessCallNotificationsViewModel = hiltViewModel()) {
    val on by vm.enabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${Glyph.DiamondOutline} process call notifications",
                style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
            )
            Text(
                text = if (on) "ON" else "OFF",
                color = if (on) MytharaColors.Bok else MytharaColors.FgMute,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.set(!on) }) {
                Text(
                    text = if (on) Glyph.CircleFilled else Glyph.CircleOutline,
                    color = if (on) MytharaColors.Bok else MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "  tap to turn ${if (on) "off" else "on"}",
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} when OFF (the default), Mythara IGNORES every phone / VoIP call notification — incoming, missed, ongoing — across the dialer app, WhatsApp / Signal / Telegram voice + video calls, etc. No agent triage, no auto-reply, no narration. When ON, call notifications flow into the same auto-process pipeline as messages — useful if you want Lumi to announce 'X is calling' over TTS. Calls are a different interaction mode; most users want this off.",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )
    }
}
