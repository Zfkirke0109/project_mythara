package com.mythara.ui.secret

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * The screen behind the triple-tap + password gate. M8.0 is a placeholder
 * — the Observe controls (continuous on-device ASR via Vosk, Gemma-driven
 * learning extraction, encrypted vault browser, "Forget everything")
 * land in M8.1+ once the audio pipeline is wired.
 *
 * Visible today so the gate is end-to-end exercisable.
 */
@Composable
fun SecretSettingsScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                text = "${Glyph.DiamondFilled} observe",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(20.dp))

        Panel("status") {
            Text(
                text = "${Glyph.CircleOutline} Observe mode is OFF.",
                color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(14.dp))

        Panel("coming in m8.1") {
            Bullet("continuous mic capture with VAD (silence-gated)")
            Bullet("offline ASR via Vosk small-model (no cloud)")
            Bullet("learning extraction via on-device MediaPipe Gemma")
            Bullet("encrypted vault browser + search")
            Bullet("'forget everything' purge")
            Bullet("pause / resume toggle + nightly compaction")
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} audio + transcripts auto-purge on schedule; only learnings persist.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.sp),
            )
        }

        Spacer(Modifier.height(14.dp))

        Panel("privacy invariants (baked into code)") {
            Bullet("observe audio + transcripts NEVER leave the device")
            Bullet("the foreground-service notification is mandatory (Android requires it)")
            Bullet("'forget everything' purges in one transaction")
            Bullet("growth jobs are pausable from this panel")
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Panel(title: String, body: @Composable () -> Unit) {
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

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = Glyph.Dot,
            color = MytharaColors.Bok,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.padding(end = 6.dp))
        Text(
            text = text,
            color = MytharaColors.FgMute,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
