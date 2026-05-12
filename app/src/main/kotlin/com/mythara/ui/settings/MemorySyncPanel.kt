package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Settings panel for the GitHub memory-sync feature. Hosted by the
 * main [SettingsScreen]; opens inline below the existing model picker.
 *
 * Two phases:
 *  - Token entry + validate (mirrors the MiniMax key pattern)
 *  - Once validated, a category toggle row + "Sync now" button + status
 *    line showing the last result and last-sync timestamp.
 */
@Composable
fun MemorySyncPanel(vm: MemorySyncViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()

    PanelLocal("memory sync") {
        Text(
            text = "${Glyph.AccentBar} learnings + settings (and optionally chat) sync to your private GitHub repo so they survive device switches. nothing secret leaves the phone.",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )

        Spacer(Modifier.height(10.dp))

        // Token
        var pat by remember { mutableStateOf("") }
        LaunchedEffect(state.pat) { if (state.pat.isNotBlank()) pat = state.pat }

        OutlinedTextField(
            value = pat,
            onValueChange = { pat = it },
            singleLine = true,
            placeholder = { Text("ghp_… (GitHub PAT, `repo` scope)", color = MytharaColors.FgDim) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MytharaColors.Fg,
                unfocusedTextColor = MytharaColors.Fg,
                focusedBorderColor = MytharaColors.Charple,
                unfocusedBorderColor = MytharaColors.SurfaceHigh,
                cursorColor = MytharaColors.Charple,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = "generate at github.com/settings/tokens · scope: repo",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim, letterSpacing = 1.sp),
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { vm.saveAndValidate(pat) },
                enabled = !state.validating && pat.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                ),
            ) {
                Text(if (state.validating) "${Glyph.Ellipsis} validating" else "${Glyph.Check} validate")
            }
            state.validation?.let { v ->
                val color = if (v.ok) MytharaColors.Julep else MytharaColors.Sriracha
                Text(
                    text = "${if (v.ok) Glyph.Check else Glyph.Cross} ${v.message}",
                    color = color,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Owner + repo
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.owner,
                onValueChange = vm::setOwner,
                singleLine = true,
                label = { Text("owner", color = MytharaColors.FgDim, style = MaterialTheme.typography.labelMedium) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Charple,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Charple,
                ),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.repo,
                onValueChange = vm::setRepo,
                singleLine = true,
                label = { Text("repo", color = MytharaColors.FgDim, style = MaterialTheme.typography.labelMedium) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Charple,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Charple,
                ),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(14.dp))

        // Sync category toggles
        ToggleRow(label = "sync learnings", on = state.syncLearnings) {
            vm.setScopes(it, state.syncSettings, state.syncChat)
        }
        ToggleRow(label = "sync settings (region, model, prefs)", on = state.syncSettings) {
            vm.setScopes(state.syncLearnings, it, state.syncChat)
        }
        ToggleRow(label = "sync chat history (sensitive — off by default)", on = state.syncChat) {
            vm.setScopes(state.syncLearnings, state.syncSettings, it)
        }

        Spacer(Modifier.height(10.dp))
        ToggleRow(label = "enable automatic nightly sync", on = state.enabled) {
            vm.setEnabled(it)
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { vm.syncNow() },
                enabled = !state.syncing && !state.restoring && state.validation?.ok == true,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Bok.copy(alpha = 0.95f), contentColor = MytharaColors.Bg,
                ),
            ) {
                Text(if (state.syncing) "${Glyph.Ellipsis} syncing" else "${Glyph.Arrow} sync now")
            }
            Button(
                onClick = { vm.openRestoreConfirm() },
                enabled = !state.syncing && !state.restoring && state.validation?.ok == true,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                ),
            ) {
                Text(if (state.restoring) "${Glyph.Ellipsis} restoring" else "${Glyph.Refresh} restore")
            }
        }
        state.lastResult?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it, color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.restoreConfirmOpen) {
            AlertDialog(
                onDismissRequest = { vm.cancelRestore() },
                title = {
                    Text(
                        text = "restore from repo?",
                        color = MytharaColors.Fg,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                text = {
                    Text(
                        text = "this pulls learnings, settings, and (if synced) chat history from ${state.owner}/${state.repo}, replacing any data on this device. the api key stays local — re-enter if needed.",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                containerColor = MytharaColors.Surface,
                confirmButton = {
                    Button(
                        onClick = { vm.confirmRestore() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Refresh} restore") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.cancelRestore() }) {
                        Text("cancel", color = MytharaColors.FgMute)
                    }
                },
            )
        }

        if (state.lastSyncTs > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "last sync: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(state.lastSyncTs))}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!on) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (on) Glyph.CircleFilled else Glyph.CircleOutline,
            color = if (on) MytharaColors.Charple else MytharaColors.FgMute,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.padding(end = 8.dp))
        Text(
            text = label,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PanelLocal(title: String, body: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} $title",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))
        body()
    }
}

private fun androidx.compose.ui.graphics.Color.copy(alpha: Float): androidx.compose.ui.graphics.Color =
    androidx.compose.ui.graphics.Color(red, green, blue, alpha)
