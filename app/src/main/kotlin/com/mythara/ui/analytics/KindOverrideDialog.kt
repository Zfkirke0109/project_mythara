package com.mythara.ui.analytics

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
 * Long-press picker that lets the user PIN a row's classifier verdict.
 * Once pinned, [com.mythara.analytics.EntityKindClassifier] returns
 * the chosen kind with confidence 1.0 on every future classify call —
 * so the EntityKindClassifier's heuristics can no longer reclassify
 * "WhatsApp Business" notifications from your tailor back into the
 * People list.
 *
 * Five canonical kinds + Clear:
 *   • person              — normal contact, visible in People
 *   • organization        — brand / business / company
 *   • place               — location / venue / address
 *   • app                 — system utility / app notification source
 *   • notification-source — generic feed (weather, news, telemarketers)
 *   • Clear pin           — drop the override, fall back to heuristics
 *
 * Visual: row swatches use the SAME palette as the Insights graph
 * legend so muscle memory carries across surfaces (Charple = person,
 * Mustard = org, Malibu = place, Bok = app, FgDim = notification).
 */
@Composable
fun KindOverrideDialog(
    row: ContactProfileRow,
    currentPin: String?,
    onDismiss: () -> Unit,
    onPin: (String) -> Unit,
    onClear: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MytharaColors.Bg)
                .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${Glyph.DiamondFilled} pin classification",
                        color = MytharaColors.Charple,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("cancel", color = MytharaColors.FgMute)
                    }
                }
                Spacer(Modifier.size(2.dp))
                Text(
                    text = row.displayName,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = currentPin?.let { "pinned as $it" } ?: "currently classified as ${row.kind}",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = "Pinning overrides the entity classifier — Mythara will respect this " +
                        "choice forever. Non-person pins also hide the row from the People list " +
                        "(restore via Hidden rows).",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (opt in KIND_OPTIONS) {
                        KindOptionRow(
                            label = opt.label,
                            sublabel = opt.sublabel,
                            swatch = opt.swatch,
                            selected = currentPin == opt.kind,
                            onClick = { onPin(opt.kind) },
                        )
                    }
                }
                if (currentPin != null) {
                    Spacer(Modifier.size(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onClear) {
                            Text(
                                text = "${Glyph.Cross} clear pin",
                                color = MytharaColors.Sriracha,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KindOptionRow(
    label: String,
    sublabel: String,
    swatch: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MytharaColors.Surface else MytharaColors.Bg)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MytharaColors.Charple else MytharaColors.SurfaceHigh,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(swatch),
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = sublabel,
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (selected) {
            Text(
                text = Glyph.Check,
                color = MytharaColors.Bok,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Option metadata — kept in one place so the labels stay consistent
 *  with the Insights graph legend + the SecretSettings cleanup
 *  report. */
private data class KindOption(
    val kind: String,
    val label: String,
    val sublabel: String,
    val swatch: androidx.compose.ui.graphics.Color,
)

private val KIND_OPTIONS: List<KindOption> = listOf(
    KindOption(
        ContactProfileRow.KIND_PERSON,
        "person",
        "real contact — stays in People list",
        MytharaColors.Charple,
    ),
    KindOption(
        ContactProfileRow.KIND_ORG,
        "organization",
        "brand / business / company (hidden from People)",
        MytharaColors.Mustard,
    ),
    KindOption(
        ContactProfileRow.KIND_PLACE,
        "place",
        "location / venue / address (hidden from People)",
        MytharaColors.Malibu,
    ),
    KindOption(
        ContactProfileRow.KIND_APP,
        "app",
        "system app / utility notification source (hidden)",
        MytharaColors.Bok,
    ),
    KindOption(
        ContactProfileRow.KIND_NOTIFICATION,
        "notification-source",
        "weather / news / generic feed (hidden from People)",
        MytharaColors.FgDim,
    ),
)
