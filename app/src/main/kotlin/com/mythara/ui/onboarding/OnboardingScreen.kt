package com.mythara.ui.onboarding

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mythara.persona.UsageAccessHelper
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.extract.gemma.GemmaModelStore
import com.mythara.secret.observe.vosk.VoskModelStore
import com.mythara.services.NotificationListener
import com.mythara.services.PhoneControlAccessibilityService
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.MytharaWordmark

/**
 * First-launch onboarding splash. Walks the user through every
 * permission, special-access toggle and optional model download that
 * Mythara needs before its tools work end-to-end. Without this screen,
 * a fresh install lands in a half-broken state where most tools fail
 * with "permission denied" and the only fix is digging through the
 * Settings panels one by one.
 *
 * Structure — a single scrollable page (no multi-step wizard, because
 * users skip ahead and skip steps; one page lets us show everything
 * with status pills + grant buttons and pivot on what's still missing):
 *
 *   1. Welcome — MYTHARA wordmark + one-liner
 *   2. Runtime permissions (one row per perm, multi-perm launcher)
 *   3. Special access (Accessibility, Notification access, Usage access)
 *   4. Optional model downloads (USE-Lite, Vosk, Gemma)
 *   5. "I'm done" button → marks OnboardingStore.completed = true →
 *      MytharaRoot pivots to AuthGate.
 *
 * The user can hit "skip for now" at the top to bypass the whole
 * screen — useful for power users who want to grant things
 * piecemeal from Settings. We never block: even with zero permissions
 * granted, the chat surface still works (just with degraded tools).
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ----- Runtime permissions -----
    // Build the set lazily because READ_MEDIA_IMAGES / POST_NOTIFICATIONS
    // are API 33+ only, and READ_EXTERNAL_STORAGE is the pre-33 fallback.
    val runtimePerms = remember { runtimePermissionList() }
    val permStatus = remember { mutableStateOf(checkRuntimePermissions(ctx, runtimePerms)) }
    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> permStatus.value = checkRuntimePermissions(ctx, runtimePerms) }

    // ----- Special access (re-checked on resume) -----
    var accessibilityListed by remember {
        mutableStateOf(isAccessibilityListed(ctx))
    }
    var notificationListed by remember {
        mutableStateOf(isNotificationAccessListed(ctx))
    }
    var usageAccess by remember {
        mutableStateOf(UsageAccessHelper(ctx).isGranted())
    }

    LaunchedEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permStatus.value = checkRuntimePermissions(ctx, runtimePerms)
                accessibilityListed = isAccessibilityListed(ctx)
                notificationListed = isNotificationAccessListed(ctx)
                usageAccess = UsageAccessHelper(ctx).isGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
    }

    val accessibilityRuntime by PhoneControlAccessibilityService.isEnabled.collectAsState()
    val notificationRuntime by NotificationListener.isEnabled.collectAsState()

    val embeddingsState by vm.embeddingsState.collectAsState()
    val voskState by vm.voskState.collectAsState()
    val gemmaState by vm.gemmaState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            // ----- Header (wordmark + skip) -----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MytharaWordmark(fontSize = 26.sp)
                TextButton(onClick = {
                    vm.complete()
                    onComplete()
                }) {
                    Text(
                        text = "skip for now →",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "welcome.",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Mythara works best when it can see your screen, hear you, " +
                    "read your notifications, and reach your contacts / SMS / " +
                    "calendar / location. Granting takes about a minute. You " +
                    "can re-run this any time from Settings.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )

            // ----- Section: runtime permissions -----
            Spacer(Modifier.height(20.dp))
            SectionHeader("${Glyph.DiamondOutline} runtime permissions")
            Spacer(Modifier.height(8.dp))
            val allRuntimeGranted = permStatus.value.values.all { it }
            Card {
                if (allRuntimeGranted) {
                    StatusLine(Glyph.Check, MytharaColors.Julep, "all granted")
                } else {
                    val missing = permStatus.value.filterValues { !it }.keys.size
                    StatusLine(Glyph.Cross, MytharaColors.Sriracha, "$missing missing")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Mic, camera, contacts, SMS, calls, calendar, location, photos, " +
                            "notifications — granted via the standard Android prompts. " +
                            "You can deny any one and Mythara keeps working with that tool disabled.",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(
                        label = "${Glyph.Arrow} grant runtime permissions",
                        onClick = { permLauncher.launch(runtimePerms.toTypedArray()) },
                    )
                }

                // Always show the per-permission rows so the user can see
                // which ones they denied (or weren't asked yet) at a glance.
                Spacer(Modifier.height(10.dp))
                for ((perm, granted) in permStatus.value) {
                    PermissionRow(
                        label = humaniseRuntimePermission(perm),
                        granted = granted,
                    )
                }
            }

            // ----- Section: special access -----
            Spacer(Modifier.height(20.dp))
            SectionHeader("${Glyph.DiamondOutline} special access (system settings)")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "These can't be granted via a popup — Android requires you to flip them on " +
                    "in the Settings app. Each row links straight to the right page.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))

            SpecialAccessCard(
                title = "screen-reading + phone control (Accessibility)",
                granted = accessibilityRuntime || accessibilityListed,
                rationale = "Required for read_screen, tap, swipe, type, screenshot_view — " +
                    "any tool that interacts with what's on your phone. The system shows a scary " +
                    "warning when you toggle this — that's normal, accept it.",
                buttonLabel = "open Accessibility settings",
                onClick = { openAccessibilitySettings(ctx) },
            )
            Spacer(Modifier.height(10.dp))
            SpecialAccessCard(
                title = "read incoming notifications",
                granted = notificationRuntime || notificationListed,
                rationale = "Required for read_notifications and the auto-process / auto-reply " +
                    "pipelines. Find Mythara in the list and flip it on.",
                buttonLabel = "open Notification access settings",
                onClick = { openNotificationAccessSettings(ctx) },
            )
            Spacer(Modifier.height(10.dp))
            SpecialAccessCard(
                title = "app usage stats",
                granted = usageAccess,
                rationale = "Powers persona-building from your phone usage patterns. Off by default; " +
                    "you'll still see a separate in-app Persona toggle even after granting here.",
                buttonLabel = "open Usage access settings",
                onClick = {
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { ctx.startActivity(intent) }
                },
            )

            // ----- Section: optional model downloads -----
            Spacer(Modifier.height(20.dp))
            SectionHeader("${Glyph.DiamondOutline} on-device models (optional)")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Pulled from Hugging Face / Google's mediapipe-models bucket. " +
                    "Small one (6 MB) is worth grabbing now; the others can wait until " +
                    "you turn on the features that need them.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))

            ModelDownloadCard(
                title = "USE-Lite text embedder (~6 MB)",
                rationale = "Powers chat recall, contact analytics, and learning dedup. " +
                    "Recommended.",
                state = embeddingsState,
                onDownload = { vm.downloadEmbedder() },
            )
            Spacer(Modifier.height(10.dp))
            ModelDownloadCard(
                title = "Vosk small English (~50 MB)",
                rationale = "Powers 'Hey Lumi' wake-word + Observe-mode offline ASR. " +
                    "Skip if you only use push-to-talk.",
                state = voskState,
                onDownload = { vm.downloadVosk() },
            )
            Spacer(Modifier.height(10.dp))
            ModelDownloadCard(
                title = "Gemma 4 E2B (~2.6 GB)",
                rationale = "Powers Observe-mode learning extraction. Big — only grab on Wi-Fi " +
                    "and only if you're using Observe mode. Can be downloaded later from " +
                    "Secret settings.",
                state = gemmaState,
                onDownload = { vm.downloadGemma() },
            )

            // ----- Footer -----
            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                label = "${Glyph.Check} I'm done — take me to Mythara",
                onClick = {
                    vm.complete()
                    onComplete()
                },
                emphasis = true,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${Glyph.AccentBar} You can re-open this walkthrough from Settings → re-run onboarding.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

// -------------------------------------------------------------------- atoms

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        color = MytharaColors.FgMute,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun StatusLine(glyph: String, color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(glyph, color = color, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(end = 6.dp))
        Text(label, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (granted) Glyph.Check else Glyph.Cross,
            color = if (granted) MytharaColors.Julep else MytharaColors.Sriracha,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.padding(end = 8.dp))
        Text(
            text = label,
            color = if (granted) MytharaColors.FgMute else MytharaColors.Fg,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SpecialAccessCard(
    title: String,
    granted: Boolean,
    rationale: String,
    buttonLabel: String,
    onClick: () -> Unit,
) {
    Card {
        StatusLine(
            glyph = if (granted) Glyph.Check else Glyph.Cross,
            color = if (granted) MytharaColors.Julep else MytharaColors.Sriracha,
            label = title,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = rationale,
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            label = if (granted) "${Glyph.Refresh} re-open settings" else "${Glyph.Arrow} $buttonLabel",
            onClick = onClick,
            emphasis = !granted,
        )
    }
}

@Composable
private fun ModelDownloadCard(
    title: String,
    rationale: String,
    state: Any,
    onDownload: () -> Unit,
) {
    val ready = when (state) {
        is EmbeddingsModelStore.State.Ready,
        is VoskModelStore.State.Ready,
        is GemmaModelStore.State.Ready -> true
        else -> false
    }
    val downloading: Pair<Int, String>? = when (state) {
        is EmbeddingsModelStore.State.Downloading -> state.pct to "${state.bytes / 1024} KB"
        is VoskModelStore.State.Downloading -> state.pct to "${state.bytes / 1024 / 1024} MB"
        is GemmaModelStore.State.Downloading -> state.pct to "${state.bytes / 1024 / 1024} MB"
        is VoskModelStore.State.Extracting -> 100 to "unpacking…"
        else -> null
    }
    val failure: String? = when (state) {
        is EmbeddingsModelStore.State.Failed -> state.message
        is VoskModelStore.State.Failed -> state.message
        is GemmaModelStore.State.Failed -> state.message
        else -> null
    }
    Card {
        StatusLine(
            glyph = when {
                ready -> Glyph.Check
                downloading != null -> Glyph.Ellipsis
                failure != null -> Glyph.Cross
                else -> Glyph.CircleOutline
            },
            color = when {
                ready -> MytharaColors.Julep
                downloading != null -> MytharaColors.Citron
                failure != null -> MytharaColors.Sriracha
                else -> MytharaColors.FgMute
            },
            label = title,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = rationale,
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        if (downloading != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (downloading.first / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MytharaColors.Charple,
                trackColor = MytharaColors.SurfaceHigh,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${downloading.first}% — ${downloading.second}",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (failure != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.Cross} $failure",
                color = MytharaColors.Sriracha,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!ready) {
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                label = if (failure != null) "${Glyph.Refresh} retry download"
                else if (downloading != null) "${Glyph.Ellipsis} downloading…"
                else "${Glyph.Arrow} download",
                onClick = onDownload,
                emphasis = failure != null || downloading == null,
            )
        }
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    emphasis: Boolean = false,
) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (emphasis) MytharaColors.Charple else MytharaColors.Surface,
            contentColor = MytharaColors.Fg,
        ),
    ) {
        Text(
            text = label,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// -------------------------------------------------------------------- helpers

private fun runtimePermissionList(): List<String> {
    val list = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list += Manifest.permission.READ_MEDIA_IMAGES
        list += Manifest.permission.POST_NOTIFICATIONS
    } else {
        list += Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return list
}

private fun checkRuntimePermissions(
    ctx: Context,
    perms: List<String>,
): Map<String, Boolean> = perms.associateWith { p ->
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
}

private fun humaniseRuntimePermission(perm: String): String = when (perm) {
    Manifest.permission.RECORD_AUDIO -> "microphone (RECORD_AUDIO)"
    Manifest.permission.CAMERA -> "camera"
    Manifest.permission.READ_CONTACTS -> "read contacts"
    Manifest.permission.SEND_SMS -> "send SMS"
    Manifest.permission.READ_SMS -> "read SMS (for one-time import)"
    Manifest.permission.CALL_PHONE -> "place calls"
    Manifest.permission.READ_CALENDAR -> "read calendar"
    Manifest.permission.WRITE_CALENDAR -> "write calendar"
    Manifest.permission.ACCESS_FINE_LOCATION -> "location (fine)"
    Manifest.permission.ACCESS_COARSE_LOCATION -> "location (coarse)"
    Manifest.permission.READ_MEDIA_IMAGES -> "read photos (READ_MEDIA_IMAGES)"
    Manifest.permission.READ_EXTERNAL_STORAGE -> "read storage (legacy ≤ API 32)"
    Manifest.permission.POST_NOTIFICATIONS -> "post notifications"
    else -> perm.substringAfterLast('.')
}

private fun openAccessibilitySettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }
}

private fun openNotificationAccessSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }
}

private fun isAccessibilityListed(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(
        ctx.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    val ourName = "${ctx.packageName}/com.mythara.services.PhoneControlAccessibilityService"
    return enabled.split(':').any { it.equals(ourName, ignoreCase = true) }
}

private fun isNotificationAccessListed(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(
        ctx.contentResolver,
        "enabled_notification_listeners",
    ).orEmpty()
    val ours = ComponentName(ctx.packageName, "com.mythara.services.NotificationListener")
        .flattenToString()
    return enabled.split(':').any { it.equals(ours, ignoreCase = true) }
}
