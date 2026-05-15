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
import androidx.compose.foundation.clickable
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
    private val fullControl: com.mythara.data.FullControlStore,
) : ViewModel() {
    val blocked: StateFlow<Set<String>> =
        store.blockedFlow().stateIn(viewModelScope, SharingStarted.Eagerly, RestrictedAppsStore.BLOCKED_DEFAULTS)

    val critical: StateFlow<Set<String>> =
        store.criticalFlow().stateIn(viewModelScope, SharingStarted.Eagerly, RestrictedAppsStore.CRITICAL_DEFAULTS)

    val blockedExtras: StateFlow<Set<String>> =
        store.blockedExtrasFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val criticalExtras: StateFlow<Set<String>> =
        store.criticalExtrasFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val fullControlEnabled: StateFlow<Boolean> =
        fullControl.enabledFlow().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun addBlocked(pkg: String) { viewModelScope.launch { store.addBlocked(pkg) } }
    fun removeBlocked(pkg: String) { viewModelScope.launch { store.removeBlocked(pkg) } }
    fun addCritical(pkg: String) { viewModelScope.launch { store.addCritical(pkg) } }
    fun removeCritical(pkg: String) { viewModelScope.launch { store.removeCritical(pkg) } }
    fun setFullControl(value: Boolean) { viewModelScope.launch { fullControl.setEnabled(value) } }
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
    val fullControl by vm.fullControlEnabled.collectAsState()

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

        Spacer(Modifier.height(14.dp))

        // Full Control — top-level "no friction" override that
        // bypasses BOTH the blocked-list veto AND the critical-list
        // confirmation popup. Off by default; user-explicit opt-in
        // only. Lives at the top of the panel because it's the
        // override above everything else below it on the page.
        FullControlToggle(
            enabled = fullControl,
            onToggle = { vm.setFullControl(!fullControl) },
        )

        Spacer(Modifier.height(12.dp))

        // Blocked — always-veto. App picker shows installed apps so
        // the user can pick (e.g.) their regional bank by name rather
        // than guessing the package id. Manual entry stays for power
        // users / packages without launcher activity.
        var blockedPickerOpen by remember { mutableStateOf(false) }
        Section(
            title = "${Glyph.Cross} blocked (never automate)",
            titleColor = MytharaColors.Sriracha,
            defaultCount = blocked.size - blockedExtras.size,
            extras = blockedExtras,
            onRemove = vm::removeBlocked,
            onAdd = vm::addBlocked,
            onPickFromInstalled = { blockedPickerOpen = true },
            placeholder = "com.examplebank.android",
        )
        if (blockedPickerOpen) {
            AppPickerSheet(
                title = "pick an app to block",
                excludePackages = blocked,
                onDismiss = { blockedPickerOpen = false },
                onPick = { pkg -> vm.addBlocked(pkg) },
            )
        }

        Spacer(Modifier.height(12.dp))

        // Critical — always-confirm
        var criticalPickerOpen by remember { mutableStateOf(false) }
        Section(
            title = "${Glyph.Refresh} critical (always confirm)",
            titleColor = MytharaColors.Mustard,
            defaultCount = critical.size - criticalExtras.size,
            extras = criticalExtras,
            onRemove = vm::removeCritical,
            onAdd = vm::addCritical,
            onPickFromInstalled = { criticalPickerOpen = true },
            placeholder = "com.example.shopping",
        )
        if (criticalPickerOpen) {
            AppPickerSheet(
                title = "pick an app to require confirmation",
                excludePackages = critical,
                onDismiss = { criticalPickerOpen = false },
                onPick = { pkg -> vm.addCritical(pkg) },
            )
        }
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
    onPickFromInstalled: () -> Unit,
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

    Spacer(Modifier.height(8.dp))
    // Primary path: pick from installed apps. Most users want a
    // visual picker, not to type a package id from memory.
    Button(
        onClick = onPickFromInstalled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MytharaColors.Charple,
            contentColor = MytharaColors.Fg,
        ),
    ) {
        Text("${Glyph.Arrow} pick from installed apps")
    }

    // Fallback: manual package-name entry. Some packages (system
    // services, work-profile apps) don't surface a launcher activity
    // and so don't appear in the picker. We keep this as a power-user
    // escape hatch, collapsed into a small secondary affordance below
    // the main button.
    Spacer(Modifier.height(6.dp))
    var manualOpen by remember { mutableStateOf(false) }
    if (!manualOpen) {
        TextButton(onClick = { manualOpen = true }) {
            Text(
                text = "${Glyph.Arrow} or enter a package id manually",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } else {
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
}

/** Top-level "Full Control" toggle row. Pulled out of the panel
 *  body so the panel reads as: explainer → override → blocked list
 *  → critical list, top-to-bottom. Visual treatment matches an
 *  in-line warning when on (Sriracha border + tag) so the user is
 *  always aware the policies are bypassed. */
@Composable
private fun FullControlToggle(enabled: Boolean, onToggle: () -> Unit) {
    val accent = if (enabled) MytharaColors.Sriracha else MytharaColors.SurfaceHigh
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, accent, RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = if (enabled) "${Glyph.CircleFilled} full control · ON" else "${Glyph.CircleOutline} full control · off",
                color = if (enabled) MytharaColors.Sriracha else MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (enabled) {
                "Mythara fires every side-effect tool with no confirmation, including in apps you've added to the blocked or critical lists. You are the only gatekeeper."
            } else {
                "Override the blocked + critical lists. Tap to flip on — every confirmation popup disappears and the agent acts immediately, even in banking / shopping apps."
            },
            style = MaterialTheme.typography.bodySmall.copy(
                color = if (enabled) MytharaColors.Sriracha else MytharaColors.FgDim,
            ),
        )
    }
}
