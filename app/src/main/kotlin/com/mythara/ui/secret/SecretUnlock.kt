package com.mythara.ui.secret

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Compose dialog that asks for the secret-mode password. On first
 * invocation it pivots to a "set a password" form (with confirm field);
 * subsequent invocations show the verify form.
 *
 * Triggers [onUnlocked] when verification succeeds, [onDismiss]
 * when the user cancels.
 */
@Composable
fun SecretUnlockDialog(
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
    vm: SecretViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.probe() }

    LaunchedEffect(state.unlocked) {
        if (state.unlocked) {
            onUnlocked()
            vm.reset()
        }
    }

    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { vm.reset(); onDismiss() },
        containerColor = MytharaColors.Surface,
        title = {
            Text(
                text = if (state.isSetupMode) "set a secret password" else "secret mode",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (state.isSetupMode)
                        "this is the password for Observe controls (later: continuous learning + memory tools). keep it different from your device PIN. minimum 6 characters."
                    else
                        "enter your secret password.",
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    placeholder = { Text("password", color = MytharaColors.FgDim) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MytharaColors.Fg,
                        unfocusedTextColor = MytharaColors.Fg,
                        focusedBorderColor = MytharaColors.Charple,
                        unfocusedBorderColor = MytharaColors.SurfaceHigh,
                        cursorColor = MytharaColors.Charple,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.isSetupMode) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        placeholder = { Text("confirm", color = MytharaColors.FgDim) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MytharaColors.Fg,
                            unfocusedTextColor = MytharaColors.Fg,
                            focusedBorderColor = MytharaColors.Charple,
                            unfocusedBorderColor = MytharaColors.SurfaceHigh,
                            cursorColor = MytharaColors.Charple,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${Glyph.Cross} $it",
                        color = MytharaColors.Sriracha,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (state.isSetupMode) vm.setup(password, confirm) else vm.verify(password)
                },
                enabled = !state.checking && password.isNotBlank() && (!state.isSetupMode || confirm.isNotBlank()),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                ),
            ) {
                Text(
                    text = when {
                        state.checking -> "${Glyph.Ellipsis} checking"
                        state.isSetupMode -> "${Glyph.Check} set + unlock"
                        else -> "${Glyph.Check} unlock"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.reset(); onDismiss() }) {
                Text("cancel", color = MytharaColors.FgMute)
            }
        },
    )
}
