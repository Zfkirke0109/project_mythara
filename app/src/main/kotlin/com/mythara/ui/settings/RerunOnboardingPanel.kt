package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.OnboardingStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RerunOnboardingViewModel @Inject constructor(
    private val store: OnboardingStore,
) : ViewModel() {
    fun reset() {
        viewModelScope.launch { store.reset() }
    }
}

/**
 * "Re-open the first-run walkthrough" panel. Lets the user re-grant
 * permissions or re-download models without hunting through individual
 * Settings sections.
 *
 * Flipping the flag back to false causes [com.mythara.ui.MytharaRoot]
 * to pivot to OnboardingScreen on the next compose pass — no Activity
 * restart, no nav state to manage.
 */
@Composable
fun RerunOnboardingPanel(vm: RerunOnboardingViewModel = hiltViewModel()) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} re-run onboarding",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} pops the first-run walkthrough back up — grant any " +
                "permissions you skipped, kick off model downloads, etc.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { vm.reset() },
            colors = ButtonDefaults.buttonColors(
                containerColor = MytharaColors.Surface,
                contentColor = MytharaColors.Fg,
            ),
        ) {
            Text("${Glyph.Refresh} re-run onboarding")
        }
    }
}
