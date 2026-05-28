package com.mythara.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.analytics.ContactProfileRepository
import com.mythara.branding.MoodSink
import com.mythara.tasks.TaskRepository
import com.mythara.ui.dashboard.DashboardTileFrame
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.MytharaWordmark
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeHubViewModel @Inject constructor(
    tasks: TaskRepository,
    contacts: ContactProfileRepository,
) : ViewModel() {
    val pendingTasks: StateFlow<Int> =
        tasks.dao.pendingCountFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val peopleCount: StateFlow<Int> =
        contacts.dao.observeAll()
            .map { rows -> rows.count { it.kind == "person" && !it.isHidden } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val mood: StateFlow<String?> = MoodSink.moodFlow
}

/**
 * The home hub — the new landing surface. A grid of live tiles where
 * Chat is one tile, not the root. Each tile glances at its subsystem
 * and taps through to the full screen. The rose amulet + right-edge
 * spine still overlay this (mounted in MytharaRoot), so navigation
 * gestures are unchanged.
 */
@Composable
fun HomeHubScreen(
    onOpenChat: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenAboutMe: () -> Unit,
    vm: HomeHubViewModel = hiltViewModel(),
) {
    val pending by vm.pendingTasks.collectAsState()
    val people by vm.peopleCount.collectAsState()
    val mood by vm.mood.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
            .padding(top = 8.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        // The brand rose — hero of the home hub. Breathes + rotates;
        // tap = talk (Chat). Long-press / swipe / PTT still flow
        // through the global amulet detector mounted in MytharaRoot.
        com.mythara.ui.amulet.RoseAmulet(
            modifier = Modifier.size(110.dp),
            sizeDp = 110.dp,
            onTap = onOpenChat,
        )
        Spacer(Modifier.height(6.dp))
        MytharaWordmark(fontSize = 30.sp, shimmer = true)
        Spacer(Modifier.height(2.dp))
        Text(
            text = mood?.let { "feeling $it" } ?: "your field intelligence",
            color = MytharaColors.FgMute,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(14.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 158.dp),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("chat") {
                HubTile("◇ chat", "talk to Mythara", MytharaColors.Charple, onOpenChat)
            }
            item("people") {
                HubTile("● people", "$people known", MytharaColors.Bok, onOpenPeople)
            }
            item("tasks") {
                HubTile("▌ tasks", if (pending > 0) "$pending open" else "all clear", MytharaColors.Mustard, onOpenTasks)
            }
            item("memory") {
                HubTile("┃ memory", "your timeline", MytharaColors.Malibu, onOpenMemory)
            }
            item("insights") {
                HubTile("◆ insights", "relationship graph", MytharaColors.Julep, onOpenInsights)
            }
            item("me") {
                HubTile("◇ about me", "your profile", MytharaColors.Citron, onOpenAboutMe)
            }
        }
    }
}

@Composable
private fun HubTile(title: String, glance: String, accent: androidx.compose.ui.graphics.Color, onTap: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1.15f)) {
        DashboardTileFrame(title = title, accent = accent, onTap = onTap) {
            Column {
                Text(
                    text = glance,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}
