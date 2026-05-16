package com.mythara.wear.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Swipe-up overflow menu shown by long-pressing the home rose.
 *
 * Five destinations laid out around the rose in clock positions —
 * the same fixed-slot arrangement the phone uses (so muscle memory
 * carries between surfaces). Tapping outside the destinations
 * closes the menu without navigating; tapping a destination both
 * navigates and dismisses.
 *
 * Layout (round watch face, 12 o'clock = top):
 *   12   →  tasks
 *    3   →  today's calendar
 *    6   →  people
 *    9   →  activity log
 *   centre → close (small rose, tap to dismiss)
 *
 * Resonance toggle is rendered as a small chip below 6 o'clock when
 * the phone has enabled the feature. Kept here (not inline on home)
 * so the home screen stays uncluttered with single-purpose tap
 * target = rose = PTT.
 */
@Composable
fun ConstellationOverlay(
    visible: Boolean,
    resonanceAvailable: Boolean,
    resonanceActive: Boolean,
    onClose: () -> Unit,
    onTasks: () -> Unit,
    onCalendar: () -> Unit,
    onPeople: () -> Unit,
    onAudit: () -> Unit,
    onToggleResonance: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.85f),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.85f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onClose),  // tap scrim → close
            contentAlignment = Alignment.Center,
        ) {
            // Clock-slot destinations. Radius is a fraction of the
            // canvas — placed via offset on a 0×0 anchor so the math
            // is identical regardless of watch size.
            ClockSlot(angleDeg = 0f, label = "tasks") {
                onTasks()
                onClose()
            }
            ClockSlot(angleDeg = 90f, label = "today") {
                onCalendar()
                onClose()
            }
            ClockSlot(angleDeg = 180f, label = "people") {
                onPeople()
                onClose()
            }
            ClockSlot(angleDeg = 270f, label = "logs") {
                onAudit()
                onClose()
            }
            // Centre: tap-to-dismiss with the brand mark.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MytharaRose(
                    modifier = Modifier
                        .size(56.dp)
                        .clickable { onClose() },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "tap to close",
                    color = Color(0xFF8E8A95),
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center,
                )
                if (resonanceAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .size(if (resonanceActive) 22.dp else 18.dp)
                            .clip(CircleShape)
                            .background(
                                if (resonanceActive) CYAN_TINT
                                else Color.White.copy(alpha = 0.18f),
                            )
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.30f),
                                CircleShape,
                            )
                            .clickable {
                                onToggleResonance()
                                onClose()
                            },
                    )
                    Text(
                        text = if (resonanceActive) "resonance on" else "resonance",
                        color = Color(0xFF8E8A95),
                        fontSize = 7.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * One destination placed at [angleDeg] degrees around the centre at
 * [SLOT_RADIUS_FRAC] of the smaller canvas dimension. Angle = 0 →
 * 12 o'clock (top); positive sweeps clockwise (matches reading
 * order for the menu).
 *
 * Each slot is a 44dp circular pill with a centred label. Tap fires
 * [onClick] (caller wires in nav + close).
 */
@Composable
private fun ClockSlot(angleDeg: Float, label: String, onClick: () -> Unit) {
    // Anchor on the centre of the parent Box; offset by (dx, dy)
    // computed from the angle. We use a sub-Box absolutely positioned
    // via Modifier.offset rather than a parent ConstraintLayout — keeps
    // dependency surface small and works under AnimatedVisibility's
    // scale animation cleanly.
    val rad = angleDeg * PI.toFloat() / 180f - PI.toFloat() / 2f  // -π/2 → 12 o'clock
    val dxDp = (cos(rad) * SLOT_RADIUS_DP_F).dp
    val dyDp = (sin(rad) * SLOT_RADIUS_DP_F).dp
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .offset(x = dxDp, y = dyDp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF1A1A1F))
                .border(1.dp, PURPLE_TINT, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, color = PURPLE_TINT, fontSize = 10.sp)
        }
    }
}

/** Slot radius in DP — chosen so a 48dp pill at this offset sits
 *  comfortably inside the typical Wear OS round canvas (192–224dp).
 *  Tested visually on Pixel Watch 4 + Galaxy Watch 7. */
private const val SLOT_RADIUS_DP_F = 60f

private val Float.dp get() = androidx.compose.ui.unit.Dp(this)

private val PURPLE_TINT = Color(0xFF6B50FF)
private val CYAN_TINT = Color(0xFF68FFD6)
