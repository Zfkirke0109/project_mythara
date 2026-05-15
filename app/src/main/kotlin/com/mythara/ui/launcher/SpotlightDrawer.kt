package com.mythara.ui.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Pull-down Spotlight-style app drawer.
 *
 * Slides in from the TOP of the screen (not the bottom like the
 * legacy [AppDrawerSheet]) so the user can pull it down with their
 * thumb without obscuring the chat composer underneath. Big search
 * field auto-focuses on open; typing filters in real-time across
 * every installed launcher app.
 *
 * Empty-state grouping (no search query):
 *   - **Work** section: apps the [WorkspaceDetector] flagged as
 *     productivity / collaboration tools — Gmail, Calendar, Slack,
 *     Notion, Outlook, Teams, etc. Surfaces first because work
 *     apps are the most-frequently-needed-by-name searches.
 *   - **Personal** section: everything else.
 *
 * Search-state: the section grouping disappears, results render as
 * a single ranked grid (prefix-match first, then substring-match,
 * label-sort within each tier).
 *
 * Voice search: deferred to next round — the mic button stub is in
 * place and will tap into the existing SpeechRecognizer wiring once
 * the speech-text bridge for in-app commands lands.
 */
@Composable
fun SpotlightDrawer(
    onDismiss: () -> Unit,
    onLaunchApp: (pkg: String) -> Unit = { /* default: launch + dismiss handled by parent */ },
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // App list — load once on first composition. The drawer is a
    // transient overlay so we don't want to re-query PackageManager
    // on every recomposition; the list stays fresh for as long as
    // the drawer is open.
    val apps by produceState(initialValue = emptyList<DrawerApp>(), key1 = Unit) {
        value = withContext(Dispatchers.IO) { loadDrawerApps(ctx) }
    }

    // Auto-bucket every app once apps load. Bucketing is cheap
    // (string ops) but we still memoize so per-keystroke recomposes
    // don't redo the work.
    val buckets by remember(apps) {
        derivedStateOf {
            apps.groupBy { WorkspaceDetector.bucketOf(it.pkg) }
        }
    }

    // Filtered + ranked results when the user types something.
    val q = query.trim().lowercase()
    val filtered by remember(q, apps) {
        derivedStateOf {
            if (q.isEmpty()) emptyList() else {
                val prefix = apps.filter { it.label.lowercase().startsWith(q) }
                val substr = apps.filter {
                    val ll = it.label.lowercase()
                    !ll.startsWith(q) && ll.contains(q)
                }
                (prefix.sortedBy { it.label.lowercase() } +
                    substr.sortedBy { it.label.lowercase() })
            }
        }
    }

    // Auto-focus the search field after the slide-in animation
    // settles so the soft keyboard rises naturally with the sheet.
    LaunchedEffect(Unit) {
        delay(120L)
        runCatching { focusRequester.requestFocus() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim — full-canvas tap target for "tap-to-close".
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MytharaColors.Bg.copy(alpha = 0.78f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        // The sheet itself — anchored to the top with a slide-in
        // animation. Stops at ~80% of screen height so the user
        // sees a sliver of the chat behind it (visual continuity +
        // a "tap below to dismiss" affordance).
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(initialOffsetY = { -it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it / 2 }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 720.dp)
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .background(MytharaColors.Surface, RoundedCornerShape(16.dp))
                    .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(16.dp))
                    .padding(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                text = "search apps — type to filter…",
                                color = MytharaColors.FgDim,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MytharaColors.Fg,
                            unfocusedTextColor = MytharaColors.Fg,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                    )
                    // Mic button — hooks into the speech-recogniser
                    // pipeline in a future round (the existing
                    // PttScreen / SpeechRecognizer wiring would
                    // populate `query` from a one-shot speech
                    // capture). For now it's a no-op + tooltip.
                    Spacer(Modifier.size(8.dp))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MytharaColors.Bg)
                            .border(1.dp, MytharaColors.Charple, RoundedCornerShape(12.dp))
                            .clickable {
                                // TODO(next round): one-shot voice
                                // capture → speech-to-text → set query.
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "🎤",
                            color = MytharaColors.Charple,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (q.isEmpty()) {
                    // Grouped view: Work first, Personal second. Each
                    // section gets a header + a 5-column grid.
                    val work = buckets[WorkspaceDetector.Bucket.Work].orEmpty()
                        .sortedBy { it.label.lowercase() }
                    val personal = buckets[WorkspaceDetector.Bucket.Personal].orEmpty()
                        .sortedBy { it.label.lowercase() }

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 76.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (work.isNotEmpty()) {
                            item(
                                key = "h-work",
                                span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                            ) {
                                SectionHeader("work · ${work.size}")
                            }
                            items(work, key = { "w-${it.pkg}" }) { app ->
                                DrawerCellCompact(app = app, onClick = {
                                    launchApp(ctx, app.pkg)
                                    onLaunchApp(app.pkg)
                                    onDismiss()
                                })
                            }
                        }
                        if (personal.isNotEmpty()) {
                            item(
                                key = "h-personal",
                                span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                            ) {
                                SectionHeader("personal · ${personal.size}")
                            }
                            items(personal, key = { "p-${it.pkg}" }) { app ->
                                DrawerCellCompact(app = app, onClick = {
                                    launchApp(ctx, app.pkg)
                                    onLaunchApp(app.pkg)
                                    onDismiss()
                                })
                            }
                        }
                    }
                } else {
                    // Search results — no grouping, ranked.
                    Text(
                        text = "${filtered.size} match${if (filtered.size == 1) "" else "es"}",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 76.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filtered, key = { "r-${it.pkg}" }) { app ->
                            DrawerCellCompact(app = app, onClick = {
                                launchApp(ctx, app.pkg)
                                onLaunchApp(app.pkg)
                                onDismiss()
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = "${Glyph.AccentBar} $text",
        color = MytharaColors.FgMute,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun DrawerCellCompact(app: DrawerApp, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Image(
            painter = app.iconPainter,
            contentDescription = app.label,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = app.label,
            color = MytharaColors.Fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

