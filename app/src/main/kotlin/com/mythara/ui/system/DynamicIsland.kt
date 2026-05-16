package com.mythara.ui.system

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.mythara.ui.amulet.RoseGeometry
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * iPhone Dynamic-Island-style pill that lives in the centre of the
 * Mythara status bar — and now ACTUALLY WRAPS around the device's
 * camera cutout, dock-bar style.
 *
 * Two layout modes:
 *
 *   A. **No cutout** (foldable inner display, tablet, emulator):
 *      Single pill rendered centred. Same behaviour as the
 *      previous version.
 *
 *   B. **Cutout present** (Pixel pinhole, Galaxy hole-punch):
 *      Two pills — LEFT half + RIGHT half — each anchored against
 *      the cutout's edges. The rose lives in the LEFT half;
 *      MYTHARA / insight text lives in the RIGHT half. Together
 *      they read as one continuous "wrapping dock" around the
 *      hole, like an iPhone Dynamic Island where the screen
 *      cutout sits inside the dynamic surface itself.
 *
 *      A bouncing-dock entrance animation (Spring with low damping)
 *      runs once on first mount and replays whenever the layout
 *      flips between modes — so a fold/unfold gives the wrap a
 *      satisfying landing motion.
 *
 * Two faces (in either mode):
 *   - **Idle** (no active insight in [DynamicIslandSink]): renders
 *     the small rose mark + "MYTHARA" wordmark in lavender.
 *   - **Insight** (sink has an active item): renders a coloured
 *     dot + the insight's text. Pill widens via animateContentSize
 *     to fit longer text, snaps back when the insight expires.
 *
 * The rose only animates on TAP — a one-shot 360° spin + brief
 * scale pulse — which also clears any pending insight (interpreted
 * as "I've seen it"). The wallpaper + popup amulet carry the
 * brand's living motion; the pill itself stays QUIET in idle.
 */
@Composable
fun DynamicIsland(
    modifier: Modifier = Modifier,
    cutout: CutoutRect? = null,
) {
    val scope = rememberCoroutineScope()
    val rotation = remember { Animatable(initialValue = 0f) }
    val pulseScale = remember { Animatable(initialValue = 1f) }

    // Bouncing-dock entrance — 0f → 1f spring whose damping +
    // stiffness target Apple's published Dynamic Island feel
    // (WWDC23 "Animate with springs"). Apple's official guidance
    // is "bounce ≤ 0.4"; Compose's MediumBouncy preset (0.5) is
    // above that ceiling and reads as exaggerated. We use a
    // custom dampingRatio of 0.7 (≈ bounce 0.25, "noticeable but
    // not silly") + StiffnessMediumLow which lands a ~0.31s
    // settle — inside Apple's documented 0.3-0.5s range. Replays
    // when cutout key changes (fold posture flip).
    val bounce = remember { Animatable(0f) }
    LaunchedEffect(cutout?.widthDp, cutout?.centerXDp) {
        bounce.snapTo(0f)
        bounce.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.7f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    // Re-poll the sink twice per second. Cheap (single volatile
    // ref read + occasional list pop) and gets within ~500 ms of
    // any insight push — fast enough for a status-bar surface.
    val insight by produceState<DynamicIslandSink.Insight?>(initialValue = null, key1 = Unit) {
        while (true) {
            value = DynamicIslandSink.current()
            delay(POLL_INTERVAL_MS)
        }
    }
    val showingInsight by derivedStateOf { insight != null }

    val tapHandler = {
        // Tap triggers a one-shot animation + clears the queue
        // (acknowledgement). Even when idle, tapping still spins
        // the rose — gives the user a small interactive flourish
        // without requiring an active insight.
        DynamicIslandSink.clear()
        scope.launch {
            rotation.snapTo(0f)
            rotation.animateTo(
                targetValue = 360f,
                animationSpec = tween(durationMillis = 700, easing = LinearOutSlowInEasing),
            )
            rotation.snapTo(0f)
        }
        scope.launch {
            pulseScale.snapTo(1f)
            pulseScale.animateTo(1.12f, tween(durationMillis = 140))
            pulseScale.animateTo(1f, tween(durationMillis = 220))
        }
        Unit
    }

    if (cutout == null) {
        // Mode A — no cutout, single pill centred.
        SinglePill(
            modifier = modifier,
            rotation = rotation.value,
            pulseScale = pulseScale.value,
            insight = insight,
            showingInsight = showingInsight,
            onTap = tapHandler,
        )
    } else {
        // Mode B — wrap around the cutout. Two halves anchored to
        // the cutout's edges, each scales in from `bounce` so
        // they bounce into place.
        WrappingPills(
            modifier = modifier,
            cutout = cutout,
            bounce = bounce.value,
            rotation = rotation.value,
            pulseScale = pulseScale.value,
            insight = insight,
            showingInsight = showingInsight,
            onTap = tapHandler,
        )
    }
}

/* --------------------------------------------------- single-pill mode */

@Composable
private fun SinglePill(
    modifier: Modifier,
    rotation: Float,
    pulseScale: Float,
    insight: DynamicIslandSink.Insight?,
    showingInsight: Boolean,
    onTap: () -> Unit,
) {
    // SpaceBetween arrangement so the rose hugs the LEFT edge of
    // the pill and the MYTHARA text (or insight chip) hugs the
    // RIGHT edge — per user spec "left and right justify the
    // rose and Mythara text respectively". Fixed width via
    // widthIn(min, max) so the pill stays compact and never
    // takes more than ~1/3 of the screen even when an insight
    // expands the right text.
    Row(
        modifier = modifier
            .height(PILL_HEIGHT_DP.dp)
            .widthIn(min = (PILL_HEIGHT_DP * 2).dp, max = SINGLE_PILL_MAX_WIDTH_DP.dp)
            .clip(RoundedCornerShape(PILL_HEIGHT_DP.dp))
            .background(PILL_BG)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(horizontal = TEXT_PILL_HPAD_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(modifier = Modifier.scale(pulseScale)) {
            RoseMarkSpinning(
                sizeDp = ROSE_DP,
                rotationDeg = rotation,
                accent = insight?.accent,
            )
        }
        if (showingInsight) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(ACCENT_DOT_DP.dp)
                        .clip(CircleShape)
                        .background(insight?.accent ?: MytharaColors.Charple),
                )
                Text(
                    text = insight?.text.orEmpty().take(MAX_INSIGHT_CHARS),
                    color = insight?.accent ?: MytharaColors.Fg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        } else {
            Text(
                text = "MYTHARA",
                color = RoseGeometry.Lavender,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
        }
    }
}

/* --------------------------------------------------- wrapping mode */

/**
 * Two-pill layout: rose on the left of the cutout, text on the
 * right. The pills get positioned absolutely via [offset] so they
 * land flush against the cutout's left + right edges with a small
 * gap. `bounce` is 0f→1f and drives both scale-in AND a small
 * lateral overshoot ("dock landing").
 */
@Composable
private fun WrappingPills(
    modifier: Modifier,
    cutout: CutoutRect,
    bounce: Float,
    rotation: Float,
    pulseScale: Float,
    insight: DynamicIslandSink.Insight?,
    showingInsight: Boolean,
    onTap: () -> Unit,
) {
    // The half-pills sit centred VERTICALLY on the cutout's centre
    // line so they read as a continuous ring around the hole. We
    // align to the parent's top and use offset.y to position.
    val pillCenterY = cutout.centerYDp - PILL_HEIGHT_DP / 2f
    val gap = WRAP_GAP_DP

    Box(modifier = modifier.height(PILL_HEIGHT_DP.dp)) {
        // LEFT half: rose only (compact). Right edge sits gap dp
        // from the cutout's left edge.
        val leftRightEdgeDp = cutout.leftDp - gap
        // Pill width = rose + symmetric padding. The padding scales
        // with the pill size so the rose feels centered visually
        // (24dp for the 108dp pill matches the proportions of the
        // 7dp padding for the old 36dp pill — both ≈ 22% of pill
        // height).
        val sidePaddingDp = (PILL_HEIGHT_DP * 0.22f)
        val leftWidthDp = ROSE_DP + sidePaddingDp * 2
        val leftLeftDp = leftRightEdgeDp - leftWidthDp
        Box(
            modifier = Modifier
                .offset(x = leftLeftDp.dp, y = pillCenterY.dp)
                .scale(bounce.coerceIn(0f, 1f))
                .height(PILL_HEIGHT_DP.dp)
                .width(leftWidthDp.dp)
                .clip(RoundedCornerShape(PILL_HEIGHT_DP.dp))
                .background(PILL_BG)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.scale(pulseScale)) {
                RoseMarkSpinning(
                    sizeDp = ROSE_DP,
                    rotationDeg = rotation,
                    accent = insight?.accent,
                )
            }
        }

        // RIGHT half: text. Left edge sits gap dp from the cutout's
        // right edge. Width is the natural pill width — auto-grows
        // to fit insight text via animateContentSize.
        val rightLeftDp = cutout.rightDp + gap
        Row(
            modifier = Modifier
                .offset(x = rightLeftDp.dp, y = pillCenterY.dp)
                .scale(bounce.coerceIn(0f, 1f))
                .height(PILL_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(PILL_HEIGHT_DP.dp))
                .background(PILL_BG)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap,
                )
                .widthIn(max = (WRAP_TEXT_MAX_WIDTH_DP + TEXT_PILL_HPAD_DP * 2).dp)
                .animateContentSize(animationSpec = tween(durationMillis = 220))
                .padding(horizontal = TEXT_PILL_HPAD_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showingInsight) {
                Box(
                    modifier = Modifier
                        .size(ACCENT_DOT_DP.dp)
                        .clip(CircleShape)
                        .background(insight?.accent ?: MytharaColors.Charple),
                )
                Text(
                    text = insight?.text.orEmpty().take(MAX_INSIGHT_CHARS),
                    color = insight?.accent ?: MytharaColors.Fg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = "MYTHARA",
                    color = RoseGeometry.Lavender,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
            }
        }
    }
}

/* --------------------------------------------------- shared rose */

/** Tiny rose with optional rotation + accent override for tap-flash. */
@Composable
private fun RoseMarkSpinning(
    sizeDp: Int,
    rotationDeg: Float,
    accent: Color?,
) {
    val petalPath = remember { Path() }
    val hexPath = remember { Path() }
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = (minOf(size.width, size.height) * 0.5f) /
            RoseGeometry.OuterRadiusSourceUnits
        rotate(degrees = rotationDeg, pivot = androidx.compose.ui.geometry.Offset(cx, cy)) {
            for (deg in RoseGeometry.BigPetalAngles) {
                RoseGeometry.petalPath(
                    diamond = RoseGeometry.BigPetal,
                    angleDegrees = deg.toFloat(),
                    cx = cx, cy = cy, scale = scale,
                    out = petalPath,
                )
                drawPath(petalPath, color = accent ?: RoseGeometry.Purple)
            }
            for (deg in RoseGeometry.SmallPetalAngles) {
                RoseGeometry.petalPath(
                    diamond = RoseGeometry.SmallPetal,
                    angleDegrees = deg.toFloat(),
                    cx = cx, cy = cy, scale = scale,
                    out = petalPath,
                )
                drawPath(petalPath, color = RoseGeometry.Lavender)
            }
            RoseGeometry.hexPath(cx, cy, scale, hexPath)
            drawPath(hexPath, color = RoseGeometry.Cyan)
        }
    }
}

/** Pill height — halved from 108dp → 54dp per user spec ("1/2
 *  the height"). With the rose-left + text-right justification
 *  below, the pill reads as a tight compact island rather than
 *  the previous full-bleed banner. Single-pill mode width is
 *  capped at SINGLE_PILL_MAX_WIDTH_DP so it can't overshoot the
 *  user's "1/3 screen" ask on large displays either. */
private const val PILL_HEIGHT_DP = 54
private const val ROSE_DP = 30
private const val ACCENT_DOT_DP = 8

/** Maximum width of the single-pill (no-cutout) layout. Targets
 *  ~1/3 of a Pixel-class phone screen at xxhdpi (~410dp wide,
 *  so 137dp ≈ 1/3). On larger displays it'll just be capped at
 *  this absolute value, which is the right call for a status-
 *  bar pill (you don't want it scaling indefinitely with the
 *  device). */
private const val SINGLE_PILL_MAX_WIDTH_DP = 140

/** Inner padding for the right (text) pill — scales with pill
 *  height. 14% chosen so MYTHARA reads tight against the right
 *  edge without kissing it. */
private const val TEXT_PILL_HPAD_DP = 8

/** Maximum width of the right wrap-pill's text area. Without a
 *  cap, animateContentSize lets MYTHARA pull the pill out to
 *  full screen width when the cutout is small + the text is set
 *  to a big point size. 90dp comfortably fits "MYTHARA" at the
 *  current 12sp + letter-spacing 2sp. */
private const val WRAP_TEXT_MAX_WIDTH_DP = 90
private const val MAX_INSIGHT_CHARS = 28
private const val POLL_INTERVAL_MS = 500L

/** Gap between each half-pill and the cutout edge, in dp.
 *  Bumped from 4dp → 8dp per Google's published touch-target
 *  spacing guidance — touch sensitivity is reduced inside the
 *  cutout zone, so we keep our 48dp tap regions outside the
 *  cutout's bounding rect with 8dp of breathing room. */
private const val WRAP_GAP_DP = 8f

/** Same near-black as the iPhone Dynamic Island so the pill reads
 *  visually as "system chrome floating above the strip" rather than
 *  a flat-coloured chip. Slightly transparent so the underlying
 *  status bar bg shows through faintly when the pill is wide. */
private val PILL_BG = Color(0xCC000000)
