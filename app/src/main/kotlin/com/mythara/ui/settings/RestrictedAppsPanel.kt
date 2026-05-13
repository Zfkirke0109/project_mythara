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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.RestrictedAppsStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RestrictedAppsViewModel @Inject constructor(
    private val store: RestrictedAppsStore,
) : ViewModel() {
    val blocked: StateFlow<Set<String>> =
        store.blockedFlow().stateIn(viewModelScope, SharingStarted.Eagerly, RestrictedAppsStore.BLOCKED_DEFAULTS)

    val critical: StateFlow<Set<String>> =
        store.criticalFlow().stateIn(viewModelScope, SharingStarted.Eagerly, RestrictedAppsStore.CRITICAL_DEFAULTS)

    val blockedExtras: StateFlow<Set<String>> =
        store.blockedExtrasFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val criticalExtras: StateFlow<Set<String>> =
        store.criticalExtrasFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun addBlocked(pkg: String) { viewModelScope.launch { store.addBlocked(pkg) } }
    fun removeBlocked(pkg: String) { viewModelScope.launch { store.removeBlocked(pkg) } }
    fun addCritical(pkg: String) { viewModelScope.launch { store.addCritical(pkg) } }
    fun removeCritical(pkg: String) { viewModelScope.launch { store.removeCritical(pkg) } }
}

/**
 * Panel showing the user's blocked + critical app lists. The defaults
 * cover the common cases (major US/EU/IN banks + ride-hailing +
 * delivery + e-commerce) and aren't shown row-by-row to keep the
 * panel readable — only USER-added extras are listed with remove
 * buttons. The user can add a custom package by typing its package
 * name (e.g. `com.regionalbank.android`).
 */
@Composable
fun RestrictedAppsPanel(vm: RestrictedAppsViewModel = hiltViewModel()) {
    val blocked by vm.blocked.collectAsState()
    val critical by vm.critical.collectAsState()
    val blockedExtras by vm.blockedExtras.collectAsState()
    val criticalExtras by vm.criticalExtras.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} restricted apps",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${Glyph.AccentBar} two policies even autopilot can't bypass. " +
                "Blocked apps (banking, payment, wallet, brokerage) are NEVER automated — open them yourself. " +
                "Critical apps (rides, delivery, orders, travel) ALWAYS pop a confirmation before Mythara taps anything, no matter the autopilot state.",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )

        Spacer(Modifier.height(12.dp))

        // Blocked — always-veto
        Section(
            title = "${Glyph.Cross} blocked (never automate)",
            titleColor = MytharaColors.Sriracha,
            defaultCount = blocked.size - blockedExtras.size,
            extras = blockedExtras,
            onRemove = vm::removeBlocked,
            onAdd = vm::addBlocked,
            placeholder = "com.examplebank.android",
        )

        Spacer(Modifier.height(12.dp))

        // Critical — always-confirm
        Section(
            title = "${Glyph.Refresh} critical (always confirm)",
            titleColor = MytharaColors.Mustard,
            defaultCount = critical.size - criticalExtras.size,
            extras = criticalExtras,
            onRemove = vm::removeCritical,
            onAdd = vm::addCritical,
            placeholder = "com.example.shopping",
        )
    }
}

@Composable
private fun Section(
    title: String,
    titleColor: androidx.compose.ui.graphics.Color,
    defaultCount: Int,
    extras: Set<String>,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
    placeholder: String,
) {
    Text(
        text = "$title  ·  $defaultCount built-in + ${extras.size} added",
        color = titleColor,
        style = MaterialTheme.typography.bodyMedium,
    )

    if (extras.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        extras.toSortedSet().forEach { pkg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pkg,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onRemove(pkg) }) {
                    Text("${Glyph.Cross}", color = MytharaColors.FgMute)
                }
            }
        }
    }

    Spacer(Modifier.height(6.dp))
    var input by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            placeholder = { Text(placeholder, color = MytharaColors.FgDim) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MytharaColors.Fg,
                unfocusedTextColor = MytharaColors.Fg,
                focusedBorderColor = MytharaColors.Charple,
                unfocusedBorderColor = MytharaColors.SurfaceHigh,
                cursorColor = MytharaColors.Charple,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(0.dp))
        Button(
            onClick = {
                onAdd(input)
                input = ""
            },
            enabled = input.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MytharaColors.Charple,
                contentColor = MytharaColors.Fg,
            ),
        ) {
            Text("${Glyph.Arrow} add")
        }
    }
}
