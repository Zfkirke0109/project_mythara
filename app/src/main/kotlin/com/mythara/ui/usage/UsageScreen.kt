package com.mythara.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.minimax.MiniMaxUsageClient
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MiniMax API usage / quota screen. Hits the platform's
 * `coding_plan/remains` endpoint with the user's existing chat API
 * key, renders one card per model with:
 *
 *   - the current 4-hour interval's used / total + how long until
 *     the bucket refills
 *   - the rolling weekly bucket's used / total + how long until
 *     the weekly window resets
 *   - colour-coded usage bar (green when there's headroom, mustard
 *     when it's getting tight, red when fully consumed)
 *
 * Refresh button at top kicks a fresh fetch — the API doesn't push,
 * so the screen would otherwise show whatever it loaded on enter.
 */
@HiltViewModel
class UsageViewModel @Inject constructor(
    private val client: MiniMaxUsageClient,
) : ViewModel() {

    sealed interface Ui {
        data object Loading : Ui
        data class Loaded(val rows: List<MiniMaxUsageClient.ModelRemaining>) : Ui
        data class Error(val message: String) : Ui
        data object NeedsApiKey : Ui
    }

    private val _ui = MutableStateFlow<Ui>(Ui.Loading)
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    fun refresh() {
        _ui.update { Ui.Loading }
        viewModelScope.launch {
            val res = client.fetch()
            _ui.update {
                res.fold(
                    onSuccess = { Ui.Loaded(it.sortedBy { r -> r.modelName }) },
                    onFailure = { e ->
                        if (e is MiniMaxUsageClient.MissingApiKey) Ui.NeedsApiKey
                        else Ui.Error(e.message ?: "request failed")
                    },
                )
            }
        }
    }
}

@Composable
fun UsageScreen(
    onBack: () -> Unit,
    vm: UsageViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
            TextButton(onClick = { vm.refresh() }) {
                Text("refresh", color = MytharaColors.Bok)
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "USAGE",
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = MytharaColors.Fg, letterSpacing = 3.sp,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${Glyph.AccentBar} MiniMax API quota across every model — interval " +
                    "(4h refill) and weekly buckets. Same key as chat, no separate setup.",
                style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
            )
        }

        Spacer(Modifier.height(12.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = ui) {
                is UsageViewModel.Ui.Loading -> {
                    Text(
                        text = "loading…",
                        color = MytharaColors.FgMute,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp),
                    )
                }
                is UsageViewModel.Ui.NeedsApiKey -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "${Glyph.DiamondOutline} no API key",
                            color = MytharaColors.Charple,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Set your MiniMax API key in Settings — usage figures " +
                                "share that same credential.",
                            color = MytharaColors.FgDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is UsageViewModel.Ui.Error -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "${Glyph.Cross} request failed",
                            color = MytharaColors.Charple,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = state.message,
                            color = MytharaColors.FgDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is UsageViewModel.Ui.Loaded -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.rows, key = { it.modelName }) { row ->
                            UsageCard(row = row)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageCard(row: MiniMaxUsageClient.ModelRemaining) {
    val intervalPct = pct(row.currentIntervalUsage, row.currentIntervalTotal)
    val weeklyPct = pct(row.currentWeeklyUsage, row.currentWeeklyTotal)
    val accent = colorForPct(maxOf(intervalPct, weeklyPct))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.5.dp, accent, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${Glyph.DiamondFilled} ${row.modelName}",
                color = accent,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "${(intervalPct * 100).toInt()}% / ${(weeklyPct * 100).toInt()}%",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        UsageBar(
            label = "interval (refills in ${formatDuration(row.remainsTime)})",
            used = row.currentIntervalUsage,
            total = row.currentIntervalTotal,
        )
        Spacer(Modifier.height(6.dp))
        UsageBar(
            label = "weekly (refills in ${formatDuration(row.weeklyRemainsTime)})",
            used = row.currentWeeklyUsage,
            total = row.currentWeeklyTotal,
        )
    }
}

@Composable
private fun UsageBar(label: String, used: Long, total: Long) {
    val frac = pct(used, total)
    val accent = colorForPct(frac)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "$used / $total",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MytharaColors.SurfaceHigh),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(accent),
            )
        }
    }
}

// ─── helpers ─────────────────────────────────────────────────────────

private fun pct(used: Long, total: Long): Float {
    if (total <= 0) return 0f
    return (used.toFloat() / total.toFloat()).coerceAtLeast(0f)
}

/** <60% green, 60-90% mustard, ≥90% charple-red. Highest of
 *  interval+weekly drives the card border so the user catches the
 *  more-pressing constraint at a glance. */
private fun colorForPct(p: Float): Color = when {
    p >= 0.90f -> MytharaColors.Charple
    p >= 0.60f -> MytharaColors.Mustard
    else -> MytharaColors.Bok
}

/** Format the `remains_time` value (seconds) as "Xh Ym" / "Xd Yh"
 *  — what the user actually wants to see is "how long until this
 *  refills", not raw seconds. */
private fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "now"
    val s = seconds
    val m = s / 60
    val h = m / 60
    val d = h / 24
    return when {
        d > 0 -> "${d}d ${h % 24}h"
        h > 0 -> "${h}h ${m % 60}m"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}
