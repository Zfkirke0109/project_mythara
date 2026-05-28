package com.mythara.ui.face

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.camera.FaceTracker
import com.mythara.mic.Tts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Exposes what the face avatar needs: the live TTS speaking state, and
 * the front-camera [FaceTracker.Pose] so the avatar can track the
 * user's actual head. Its own tiny ViewModel so the Face screen is a
 * standalone destination, independent of the chat surface.
 */
@HiltViewModel
class FaceViewModel @Inject constructor(
    tts: Tts,
    private val tracker: FaceTracker,
    private val pickupDetector: com.mythara.camera.PhonePickupDetector,
) : ViewModel() {
    val speaking: StateFlow<Boolean> =
        tts.speaking.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Smoothed front-camera head pose. `present = false` when no face. */
    val pose: StateFlow<FaceTracker.Pose> = tracker.pose

    /** True while the pickup detector says the camera is allowed to
     *  run. The screen binds/unbinds CameraX based on this so a
     *  phone-down idle never streams frames. */
    val cameraActive: StateFlow<Boolean> = pickupDetector.activeWindow

    /** Start / stop the front-camera stream — driven by the screen's
     *  composition lifetime so the camera only runs while it's open. */
    fun bindCamera() = tracker.bind()
    fun unbindCamera() = tracker.unbind()

    /** Start / stop the pickup-detection sensor. Tied to the
     *  Composable's DisposableEffect so it runs only while Home /
     *  Face is in the foreground. */
    fun enablePickupDetector() = pickupDetector.enable()
    fun disablePickupDetector() = pickupDetector.disable()

    override fun onCleared() {
        tracker.unbind()
        pickupDetector.disable()
    }
}

// Status-text colour constants kept here so the FaceScreen wrapper's
// status pill at the bottom of the cinema view can use them. The
// legacy point-cloud model classes (FGroup, FPoint, FModel,
// EYE_CY / EYE_DX, drawFaceCloud, buildFaceModel) were removed in
// v7 P7 polish — the particle FaceMesh below replaced them.

private val CLOUD = Color(0xFF4FE2FF)      // electric cyan — used by FaceScreen status text
private val EYE_GLOW = Color(0xFFE4FBFF)   // near-white cyan — used by FaceScreen status text

/**
 * The Mythara face — a full-screen, alternate interface to the agent.
 *
 * A glowing point-cloud humanoid head on a pure-black field. With the
 * front-camera permission granted it **tracks the user's real face**:
 * ML Kit head euler angles drive the cloud's yaw / pitch / roll, and
 * the eye-open probabilities drive its blink — so the avatar mirrors
 * you in real time. Without the camera (or with no face in frame) it
 * falls back to a gentle idle sway + self-driven blink. The lower lip
 * still drops open while Mythara is speaking. Pure Compose Canvas.
 */
@Composable
fun FaceScreen(onBack: () -> Unit, vm: FaceViewModel = hiltViewModel()) {
    val speaking by vm.speaking.collectAsState()
    val pose by vm.pose.collectAsState()
    val ctx = LocalContext.current

    // Front-camera permission — needed to track the user's face.
    var hasCam by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val camLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCam = granted }
    LaunchedEffect(Unit) { if (!hasCam) camLauncher.launch(Manifest.permission.CAMERA) }

    // Phone-pickup detector — same low-power gate as Home. The
    // camera only binds while the pickup window is open; without
    // it, the cinema view would burn the lens continuously even
    // when the phone is sitting on a desk.
    DisposableEffect(Unit) {
        vm.enablePickupDetector()
        onDispose { vm.disablePickupDetector() }
    }
    val cameraActive by vm.cameraActive.collectAsState()

    // Camera runs ONLY while this screen is composed AND the
    // permission is held AND a pickup window is open.
    DisposableEffect(hasCam, cameraActive) {
        if (hasCam && cameraActive) vm.bindCamera() else vm.unbindCamera()
        onDispose { vm.unbindCamera() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        FaceMesh(speaking = speaking, pose = pose, modifier = Modifier.fillMaxSize())

        // Phase C — back affordance moved to the MytharaScaffold
        // header sliver at the top; the in-screen "‹ chat" chip is
        // removed so the cinema view stays full-bleed.

        val status = when {
            speaking -> "● speaking"
            pose.present -> "● tracking you"
            hasCam -> "○ looking for you…"
            else -> "○ tap to enable face tracking"
        }
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium.copy(
                color = if (speaking || pose.present) EYE_GLOW else CLOUD.copy(alpha = 0.55f),
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp)
                .clickable(enabled = !hasCam) {
                    camLauncher.launch(Manifest.permission.CAMERA)
                },
        )
    }
}

/**
 * Particle face (v7). A field of glowing particles drifts freely
 * across the whole canvas. The moment the front camera detects a face
 * ([pose].present) the particles SNAP toward the centre and assemble
 * into two eyes + a mouth; lose the face and they scatter back out.
 * The eyes track the detected face (pupils aim with the head's
 * yaw/pitch) and blink with the real eye-open signal. The mouth is a
 * horizontal band of particles that, while Mythara is [speaking],
 * ripples like an audio waveform with a colour gradient sweeping along
 * it.
 *
 * Transparent canvas — the caller owns the background.
 */
@Composable
fun FaceMesh(
    speaking: Boolean,
    pose: FaceTracker.Pose,
    modifier: Modifier = Modifier,
) {
    val particles = remember { buildParticles() }
    // SHAPE particles' indices + a per-coordinate-axis backing store.
    // The shape coords get re-rolled each new session below; keeping
    // them as flat FloatArrays avoids per-frame allocations on the
    // ~430-particle hot path.
    val shapeIndices = remember(particles) {
        particles.indices.filter { particles[it].role == PRole.SHAPE }.toIntArray()
    }
    val shapeXs = remember(shapeIndices.size) { FloatArray(shapeIndices.size) }
    val shapeYs = remember(shapeIndices.size) { FloatArray(shapeIndices.size) }
    val shapeZs = remember(shapeIndices.size) { FloatArray(shapeIndices.size) }
    // Per-session shape kind + rotation axis. Re-rolled each time the
    // face-detect transitions absent → present, so the user sees a
    // different 3D shape spinning around a different axis every time
    // they pick the phone up. Initial roll at composition time.
    var sessionId by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    LaunchedEffect(pose.present) {
        if (pose.present) sessionId++
    }
    var shapeKindLabel by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(ParticleShapes.Kind.Icosahedron)
    }
    val rotationAxis = remember { FloatArray(3) }
    LaunchedEffect(sessionId) {
        val rnd = Random(System.nanoTime() xor sessionId.toLong().shl(13))
        val kinds = ParticleShapes.Kind.entries.toTypedArray()
        val kind = kinds[rnd.nextInt(kinds.size)]
        shapeKindLabel = kind
        ParticleShapes.sampleShape(
            kind = kind,
            n = shapeIndices.size,
            radius = SHAPE_RADIUS,
            rnd = rnd,
            xs = shapeXs, ys = shapeYs, zs = shapeZs,
        )
        ParticleShapes.randomUnitVector(rnd, rotationAxis)
    }
    // Scratch buffer the per-frame Rodrigues rotation writes into.
    val rotScratch = remember { FloatArray(3) }

    // Continuous frame time (seconds) — drives drift + the soundwave.
    var timeSec by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableFloatStateOf(0f)
    }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                timeSec = (now - start) / 1_000_000_000f
            }
        }
    }

    // Assembly 0 (scattered) → 1 (formed). v7 P7+: was a snappy
    // spring (dampingRatio=0.62, stiffness=190) — particles
    // appeared to teleport into the ring. The user asked for a
    // slow, smooth gathering so the formation reads as deliberate.
    // Asymmetric easing: ~1.8 s to gather (FastOutSlowInEasing —
    // accelerate, then settle), ~0.8 s to disperse (snappier so
    // looking away feels responsive). Drives the `ease` field in
    // the Canvas below where every particle's final position is
    // sxN + (txN - sxN) * ease.
    val assembly by animateFloatAsState(
        targetValue = if (pose.present) 1f else 0f,
        animationSpec = if (pose.present) {
            tween(
                durationMillis = 1800,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            )
        } else {
            tween(
                durationMillis = 800,
                easing = androidx.compose.animation.core.LinearOutSlowInEasing,
            )
        },
        label = "assembly",
    )

    // Gather phase — drives a short "drift toward centre" pre-pass
    // before particles snap onto their final ring / sphere
    // positions. The Canvas blends each particle's scatter point
    // first toward CIRCLE_CX/CY (gather), then onto its assembled
    // target (form). Curve: rises fast to ~0.55 (particles converge
    // toward middle), then dips back to 0 as `assembly` itself
    // finishes settling onto the ring. Net effect: "dust drifts
    // inward → coalesces into the ring", not "dust teleports".
    val gather = remember(assembly) {
        // 0 → 1.5 region maps to: rapid rise from 0 to ~0.55, then
        // tapering back to 0. Implemented inline so it never lags
        // the assembly state.
        val a = assembly.coerceIn(0f, 1f)
        // Triangular pulse: peaks at a=0.5, zero at 0 and 1.
        val peak = 0.55f
        (1f - kotlin.math.abs(a - 0.5f) * 2f).coerceIn(0f, 1f) * peak
    }

    // Idle self-blink when no camera face; real eye-open when tracking.
    val idleBlink = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2600L, 6000L))
            idleBlink.animateTo(0.08f, tween(80))
            idleBlink.animateTo(1f, tween(160))
        }
    }
    val tracking = pose.present
    val eyeOpenL = if (tracking) pose.leftEyeOpen else idleBlink.value
    val eyeOpenR = if (tracking) pose.rightEyeOpen else idleBlink.value
    // v7.3 — flowAmount drives the sunburst. 0 (idle) = quiet ring;
    // 1 (speaking) = particles continuously radiate outward.
    val flowAmount by animateFloatAsState(
        targetValue = if (speaking) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "flowAmount",
    )

    // Theme-adaptive gradient — pull stops from the active skin's
    // palette so the ring + sunburst always look "right" against the
    // current backdrop (rose on Living Rose, cyan on HUD, etc).
    val palette = com.mythara.ui.theme.LocalMythPalette.current
    val ringStops = remember(palette) {
        listOf(
            palette.Charple, palette.Malibu, palette.Bok,
            palette.Mustard, palette.Sriracha, palette.Charple,
        )
    }

    // Gaze — drives the inner sphere's positional offset so it
    // visibly tracks the user's head movement.
    val gazeX = pose.yaw.coerceIn(-1f, 1f)
    val gazeY = pose.pitch.coerceIn(-1f, 1f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val ease = assembly.coerceIn(0f, 1f)
        // Pre-compute the per-frame rotation factor for SHAPE
        // particles. Cached outside the loop because every SHAPE
        // particle uses the same (cosA, sinA) pair this frame.
        val rotAng = timeSec * SHAPE_ROT_HZ * 2f * PI.toFloat()
        val cosA = cos(rotAng)
        val sinA = sin(rotAng)
        val axKx = rotationAxis[0]
        val axKy = rotationAxis[1]
        val axKz = rotationAxis[2]
        // Index into shapeXs/shapeYs/shapeZs — incremented per
        // SHAPE particle seen during the iteration.
        var shapeI = 0
        for (p in particles) {
            // Scatter position — slow Lissajous drift across the screen.
            val sxN = p.homeX + p.driftAmpX * sin(timeSec * p.driftFreqX + p.driftPhaseX)
            val syN = p.homeY + p.driftAmpY * cos(timeSec * p.driftFreqY + p.driftPhaseY)

            var txN = sxN
            var tyN = syN
            // Default to a theme-driven soft ambient hue (palette
            // Charple at the muted alpha used below) instead of the
            // old hardcoded lavender — so the scattered field carries
            // the active skin's brand colour rather than a fixed one.
            var color = palette.Charple
            var coreA: Float
            // SHAPE particles fade in as they converge from scatter
            // into the rotating shape; HALO ignores this.
            var cycleAlpha = 1f
            when (p.role) {
                PRole.HALO -> {
                    color = palette.Charple
                    coreA = 0.30f * (1f - 0.4f * ease) * p.glow
                }
                PRole.SHAPE -> {
                    val aspect = w / h
                    // Unified 3D shape: rotate the particle's shape-
                    // space coordinate around the session-randomised
                    // axis. Same axis + rotation for every particle so
                    // the entire shape spins as one rigid body. The
                    // shape itself was chosen at random for this
                    // session — tetrahedron / cube / octahedron /
                    // icosahedron / torus / trefoil knot — so the
                    // user sees a different form each time they pick
                    // up the phone to look at it.
                    val baseX = shapeXs[shapeI]
                    val baseY = shapeYs[shapeI]
                    val baseZ = shapeZs[shapeI]
                    rodriguesRotate(
                        baseX, baseY, baseZ,
                        axKx, axKy, axKz,
                        cosA, sinA,
                        rotScratch,
                    )
                    val rx = rotScratch[0]
                    val ry = rotScratch[1]
                    val rz = rotScratch[2]
                    // Pseudo-3D depth: front (rz>0) brighter, back
                    // (rz<0) dimmer. Same depth logic the old SPHERE
                    // role used, applied uniformly to the whole shape.
                    val depth01 = ((rz / SHAPE_RADIUS) + 1f) * 0.5f
                    txN = CIRCLE_CX + rx + gazeX * SHAPE_GAZE_MULT
                    tyN = CIRCLE_CY + ry * aspect + gazeY * SHAPE_GAZE_MULT * aspect
                    color = particleColor(ringStops, p.hueU, timeSec, speaking)
                    coreA = (0.30f + 0.65f * depth01) * p.glow
                    // While speaking, gently breathe the shape's
                    // alpha so the user reads "active" without the
                    // disruptive outward sunburst the old CIRCLE
                    // role used.
                    val breathe = 1f + 0.18f * sin(timeSec * 6.0f) * flowAmount
                    cycleAlpha = breathe
                    shapeI++
                }
            }

            // Both roles blend scatter → assembled by `ease`. For HALO,
            // txN == sxN (no assembled target) so this is a no-op. For
            // CIRCLE / SPHERE, ease=0 (no face) → particles scatter and
            // drift; ease=1 (face detected) → particles form the ring
            // (or the sunburst, when also speaking).
            //
            // GATHER pre-pass — pull each particle a small amount
            // toward the centre while the assembly anim is mid-flight.
            // `gather` peaks at 0.55 when assembly ≈ 0.5, falls back
            // to 0 at the ends. So the visible motion reads as:
            //   1) particles all drift inward toward CIRCLE_CX/CY
            //   2) they coalesce, then push outward onto the ring
            // instead of teleporting in a single straight line.
            // HALO ignored (its `txN==sxN` so the lerp below is a
            // no-op for it; only CIRCLE/SPHERE see the pull).
            val scatterPullX = if (p.role == PRole.HALO) sxN
                else sxN + (CIRCLE_CX - sxN) * gather
            val scatterPullY = if (p.role == PRole.HALO) syN
                else syN + (CIRCLE_CY - syN) * gather
            val nx = scatterPullX + (txN - scatterPullX) * ease
            val ny = scatterPullY + (tyN - scatterPullY) * ease
            val cx = nx * w
            val cy = ny * h

            val baseR = p.size * minOf(w, h)
            val a = cycleAlpha
            drawCircle(color.copy(alpha = ((0.08f + 0.10f * coreA) * a).coerceIn(0f, 0.5f)), baseR * 2.6f, Offset(cx, cy))
            drawCircle(color.copy(alpha = ((0.40f * coreA + 0.08f) * a).coerceIn(0f, 0.9f)), baseR * 1.4f, Offset(cx, cy))
            drawCircle(color.copy(alpha = ((0.85f * coreA + 0.10f) * a).coerceIn(0f, 1f)), baseR, Offset(cx, cy))
        }
    }
}

// --------------------------------------------------- particle model

/** Role decides how a particle behaves. v7.4 → v7.5: simplified
 *  to ambient [HALO] drift + a single unified [SHAPE] that all
 *  foreground particles assemble into. The shape itself (cube /
 *  torus / icosahedron / ...) is picked at random each face-detect
 *  session — see ParticleShapes — and rotates around a random axis.
 *  The previous ring + sphere split was a fixed two-form design; the
 *  new design surprises the user with a different geometry each
 *  time they pick up the phone. */
private enum class PRole { HALO, SHAPE }

private class FParticle(
    val homeX: Float, val homeY: Float,        // scatter anchor (0..1 screen)
    val driftAmpX: Float, val driftAmpY: Float,
    val driftFreqX: Float, val driftFreqY: Float,
    val driftPhaseX: Float, val driftPhaseY: Float,
    val role: PRole,
    val size: Float,                            // radius as fraction of min(w,h)
    val glow: Float = 1f,                       // per-particle brightness scalar
    // CIRCLE-specific:
    val targetAngle: Float = 0f,                // position on inner ring [0, 2π]
    val outerAngle: Float = 0f,                 // angle of the outer spawn point
    val cyclePhase: Float = 0f,                 // [0,1] per-particle stagger
    val cycleSpeed: Float = 0f,                 // fly-in cycles per second
    val hueU: Float = 0f,                       // [0,1] color sweep position
    // SPHERE-specific: 3D coordinates on/in the cluster, normalised
    // so they stay inside the sphere's radius. Rotated around Y at
    // draw time → projected to 2D.
    val sxv: Float = 0f, val syv: Float = 0f, val szv: Float = 0f,
)

// v7.5 — unified SHAPE renderer. All foreground particles assemble
// into ONE random 3D shape (cube / torus / icosahedron / ...) that
// spins around a random axis. The shape is re-rolled on every face-
// detect transition so the user sees a different geometry each time
// they pick the phone up to look at it. HALO ambient drift stays
// unchanged for the background atmospheric layer.
private const val CIRCLE_CX = 0.50f
private const val CIRCLE_CY = 0.42f

/** Bounding radius of the assembled shape in normalised X (the Y
 *  coord is aspect-corrected at draw time so the shape stays visually
 *  round regardless of phone aspect). Picked to match the old CIRCLE_
 *  RADIUS footprint so the visual centre-of-mass on Home stays the
 *  same after the redesign. */
private const val SHAPE_RADIUS = 0.22f

/** Shape rotation rate (Hz) — one full turn every ~3.3 s. Slower than
 *  a hand fidget, faster than continental drift; gives the shape
 *  obvious volume while still feeling calm. */
private const val SHAPE_ROT_HZ = 0.30f

/** How strongly the shape's centre offsets with head pose. The whole
 *  assembled shape (not just an inner cluster anymore) tracks the
 *  user's head — so the geometry visibly leans toward them as they
 *  move. */
private const val SHAPE_GAZE_MULT = 0.085f

/** Foreground particle count. Used to be 110 (sphere) + 320 (circle)
 *  = 430 — keep the same total so the shape reads with similar
 *  visual density to the old ring + sphere. */
private const val SHAPE_PARTICLE_COUNT = 430


/** Mouth waveform y-offset (normalised height) at position [u] in
 *  [-1,1]. Always animated — even idle the ribbon undulates gently;
 *  while speaking the amplitude + speed jump and the multi-harmonic
 *  shape gives a true audio-visualiser look. */
private fun mouthWave(u: Float, t: Float, speaking: Boolean): Float {
    val pi = PI.toFloat()
    val taper = 1f - u * u * 0.45f
    return if (speaking) {
        val w = sin(u * pi * 3f - t * 9f) * 0.55f +
            sin(u * pi * 7f + t * 5.4f) * 0.34f +
            sin(u * pi * 13f - t * 13f) * 0.20f +
            sin(u * pi * 19f + t * 17f) * 0.12f
        w * 0.060f * taper
    } else {
        val w = sin(u * pi * 2f + t * 1.4f) * 0.5f +
            sin(u * pi * 4f - t * 0.9f) * 0.25f
        w * 0.024f * taper
    }
}

/** Mouth colour at [u] — a chromatic gradient (blue → purple →
 *  magenta → pink → orange → yellow) that ALWAYS sweeps along the
 *  ribbon, faster while speaking. The full-spectrum sweep is the
 *  signature look from the reference image. */
private fun mouthColor(u: Float, t: Float, speaking: Boolean): Color {
    val stops = MOUTH_STOPS
    val sweepSpeed = if (speaking) 0.22f else 0.07f
    val phase = (u + 1f) * 0.5f + t * sweepSpeed
    val f = ((phase % 1f) + 1f) % 1f
    val scaled = f * (stops.size - 1)
    val i = scaled.toInt().coerceIn(0, stops.size - 2)
    val frac = scaled - i
    return androidx.compose.ui.graphics.lerp(stops[i], stops[i + 1], frac)
}

/** Full-spectrum chromatic stops — legacy hardcoded gradient (dead;
 *  v7.3 reads stops from the active skin's palette at draw time). */
@Suppress("unused")
private val MOUTH_STOPS = listOf(
    Color(0xFF4A6BFF), // electric blue
    Color(0xFF8240FF), // purple
    Color(0xFFE040A0), // magenta
    Color(0xFFFF3060), // hot pink / red
    Color(0xFFFF8030), // orange
    Color(0xFFFFD040), // yellow
    Color(0xFF4A6BFF), // loop back to blue for continuous sweep
)

/** Sample a colour along the theme-adaptive gradient [stops] at
 *  position [u] (0..1), with a time-driven phase shift so the
 *  gradient sweeps around the ring. Sweep is slow when idle, faster
 *  while [speaking]. */
private fun particleColor(stops: List<Color>, u: Float, t: Float, speaking: Boolean): Color {
    if (stops.size < 2) return stops.firstOrNull() ?: Color.White
    val sweepSpeed = if (speaking) 0.22f else 0.07f
    val phase = u + t * sweepSpeed
    val f = ((phase % 1f) + 1f) % 1f
    val scaled = f * (stops.size - 1)
    val i = scaled.toInt().coerceIn(0, stops.size - 2)
    val frac = scaled - i
    return androidx.compose.ui.graphics.lerp(stops[i], stops[i + 1], frac)
}

/** Build the particle field once. v7 abstract redesign:
 *  - ~600 very fine particles for a flowing ribbon look (reference)
 *  - Eyes = SOFT Gaussian clusters (no rigid rings) → glowing orbs
 *  - Mouth = a THICK ribbon (vertical jitter) carrying the full
 *    chromatic gradient + multi-harmonic wave
 *  - Halo = denser ambient drift so the un-assembled field reads as
 *    a living particle cloud, not sparse dots
 *  All particles share the same tiny radius range so individual dots
 *  vanish into the band and the whole reads as fluid. */
private fun buildParticles(): List<FParticle> {
    val rnd = Random(11)
    val out = ArrayList<FParticle>(640)

    fun drift(): FloatArray = floatArrayOf(
        rnd.nextFloat(),                       // homeX
        rnd.nextFloat(),                       // homeY
        0.04f + rnd.nextFloat() * 0.11f,       // driftAmpX
        0.05f + rnd.nextFloat() * 0.13f,       // driftAmpY
        0.15f + rnd.nextFloat() * 0.55f,       // driftFreqX
        0.15f + rnd.nextFloat() * 0.55f,       // driftFreqY
        rnd.nextFloat() * 6.2832f,             // driftPhaseX
        rnd.nextFloat() * 6.2832f,             // driftPhaseY
    )

    // ─── Ambient halo (drifts everywhere, never converges) ──────────
    repeat(140) {
        val d = drift()
        out += FParticle(
            homeX = d[0], homeY = d[1],
            driftAmpX = d[2], driftAmpY = d[3],
            driftFreqX = d[4], driftFreqY = d[5],
            driftPhaseX = d[6], driftPhaseY = d[7],
            role = PRole.HALO,
            size = 0.0018f + rnd.nextFloat() * 0.0022f,
            glow = 0.5f + rnd.nextFloat() * 0.6f,
        )
    }

    // ─── SHAPE particles — assemble into a random 3D form ──────────
    // The actual (sxv, syv, szv) coordinates are written into a
    // backing FloatArray inside FaceMesh on every face-detect session
    // (see ParticleShapes.sampleShape). Here we only allocate the
    // particle envelopes — drift parameters for the scatter state,
    // size + glow + a hue position for the chromatic sweep. hueU is
    // distributed linearly across the foreground particles so the
    // colour sweep walks the whole spectrum around the shape.
    repeat(SHAPE_PARTICLE_COUNT) { k ->
        val d = drift()
        val hue = (k.toFloat() / SHAPE_PARTICLE_COUNT + rnd.nextFloat() * 0.04f) % 1f
        out += FParticle(
            homeX = d[0], homeY = d[1],
            driftAmpX = d[2], driftAmpY = d[3],
            driftFreqX = d[4], driftFreqY = d[5],
            driftPhaseX = d[6], driftPhaseY = d[7],
            role = PRole.SHAPE,
            size = 0.0018f + rnd.nextFloat() * 0.0022f,
            glow = 0.70f + rnd.nextFloat() * 0.50f,
            hueU = hue,
        )
    }
    return out
}

