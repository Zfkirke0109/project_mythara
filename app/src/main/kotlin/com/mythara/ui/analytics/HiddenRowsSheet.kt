package com.mythara.ui.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mythara.analytics.ContactProfileRow
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Dialog overlay surfacing every soft-hidden contact row so the
 * user can restore individual entries that the
 * [com.mythara.analytics.PeopleCleanupRunner] classified as
 * non-person. Each row shows its assigned kind + classifier
 * confidence + a "restore as person" affordance.
 *
 * Reads-only on its inputs — the actual restore happens via
 * [PeopleViewModel.restoreHiddenRow], which flips
 * `is_hidden = false` and stamps `kind = person` so the next
 * cleanup pass doesn't re-demote.
 *
 * Renders as a full-bleed Dialog because the People screen's
 * scaffold is already overflowing with the contact list; a
 * bottom-sheet would compete with the keyboard / amulet bottom
 * area. The Dialog gives the hidden-rows browser its own focused
 * surface.
 */
@Composable
fun HiddenRowsSheet(
    rows: List<ContactProfileRow>,
    onDismiss: () -> Unit,
    onRestore: (nameKey: String) -> Unit,
    /** Long-press a hidden row to pin its classification — e.g.
     *  "always classify <X> as organization". Optional so older
     *  call sites still compile. */
    onLongPressRow: (ContactProfileRow) -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 640.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MytharaColors.Bg)
                .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${Glyph.DiamondFilled} hidden non-people (${rows.size})",
                        color = MytharaColors.Charple,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("done", color = MytharaColors.FgMute)
                    }
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "Rows the entity classifier flagged as something other than a real person. " +
                        "Tap restore to pull a row back into the People list.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(10.dp))
                if (rows.isEmpty()) {
                    Text(
                        text = "Nothing hidden right now.",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(items = rows, key = { it.nameKey }) { row ->
                            HiddenRowTile(
                                row = row,
                                onRestore = { onRestore(row.nameKey) },
                                onLongPress = { onLongPressRow(row) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HiddenRowTile(
    row: ContactProfileRow,
    onRestore: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.displayName,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            val confPct = (row.kindConfidence * 100).toInt()
            Text(
                text = "${kindGlyph(row.kind)} ${row.kind} · $confPct% confidence",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.size(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MytharaColors.Charple.copy(alpha = 0.18f))
                .border(1.dp, MytharaColors.Charple.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .clickable { onRestore() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = "${Glyph.Arrow} restore",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

private fun kindGlyph(kind: String): String = when (kind) {
    ContactProfileRow.KIND_PLACE -> "📍"
    ContactProfileRow.KIND_ORG -> "🏢"
    ContactProfileRow.KIND_APP -> "📱"
    ContactProfileRow.KIND_NOTIFICATION -> "🔔"
    ContactProfileRow.KIND_PERSON -> "👤"
    else -> "◇"
}
