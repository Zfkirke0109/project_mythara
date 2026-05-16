package com.mythara.ui.analytics

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.analytics.ContactAnalyticsBuilder
import com.mythara.analytics.ContactProfileRepository
import com.mythara.analytics.ContactProfileRow
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val repo: ContactProfileRepository,
    private val builder: ContactAnalyticsBuilder,
    @ApplicationContext private val appContext: Context,
    /** Source for the per-turn live-persona records the
     *  PersonaTraitExtractor writes after every chat turn. Filtered
     *  per-contact when the user selects a profile. */
    private val vault: com.mythara.secret.observe.vault.LearningVault,
) : ViewModel() {
    val profiles: StateFlow<List<ContactProfileRow>> =
        repo.dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Per-turn-derived persona snapshot for a single contact. Lifted
     * straight from [com.mythara.ui.about.AboutMeViewModel.LivePersona]
     * but `target:contact:<nameKey>` instead of `target:self`. UI
     * shape is identical so we render with the same panel composable.
     */
    data class ContactLivePersona(
        val nameKey: String,
        val bigFive: List<com.mythara.ui.about.AboutMeViewModel.TraitScore>,
        val values: List<com.mythara.ui.about.AboutMeViewModel.TraitScore>,
        val preferences: List<com.mythara.ui.about.AboutMeViewModel.PreferenceEntry>,
        val concerns: List<com.mythara.ui.about.AboutMeViewModel.TraitScore>,
        val observations: Int,
    )

    private val _selectedLivePersona =
        MutableStateFlow<ContactLivePersona?>(null)
    val selectedLivePersona: StateFlow<ContactLivePersona?> =
        _selectedLivePersona.asStateFlow()

    /** Called from PeopleScreen when the user picks a contact. Pulls
     *  every `kind:trait` + `target:contact:<nameKey>` row from the
     *  vault and aggregates it the same way AboutMe does for self. */
    fun loadLivePersonaFor(nameKey: String) {
        viewModelScope.launch {
            _selectedLivePersona.value = withContext(Dispatchers.IO) {
                buildContactPersona(nameKey)
            }
        }
    }

    private suspend fun buildContactPersona(nameKey: String): ContactLivePersona? {
        val all = runCatching {
            vault.listByTier(com.mythara.memory.Tier.Semantic, limit = 1000)
        }.getOrDefault(emptyList())
        val rows = all
            .filter { row ->
                val facets = vault.decodeFacets(row)
                "kind:trait" in facets && "target:contact:$nameKey" in facets
            }
        if (rows.isEmpty()) return null

        data class Bucket(var score: Int = 0, var seen: Int = 0)
        val bigFiveBuckets = mutableMapOf<String, Bucket>()
        val valueBuckets = mutableMapOf<String, Bucket>()
        val prefBuckets = mutableMapOf<Pair<String, String>, Int>()
        val concernBuckets = mutableMapOf<String, Int>()

        for (row in rows) {
            val facets = vault.decodeFacets(row)
            val dim = facets.firstOrNull { it.startsWith("dim:") }?.removePrefix("dim:")
            val seen = row.seen.coerceAtLeast(1)
            when (dim) {
                "big5" -> {
                    val trait = facets.firstOrNull { it.startsWith("trait:") }?.removePrefix("trait:") ?: continue
                    val polarity = facets.firstOrNull { it.startsWith("polarity:") }?.removePrefix("polarity:")
                    val sign = if (polarity == "high") 1 else if (polarity == "low") -1 else 0
                    val b = bigFiveBuckets.getOrPut(trait) { Bucket() }
                    b.score += sign * seen
                    b.seen += seen
                }
                "values" -> {
                    val v = facets.firstOrNull { it.startsWith("value:") }?.removePrefix("value:") ?: continue
                    val b = valueBuckets.getOrPut(v) { Bucket() }
                    b.score += seen
                    b.seen += seen
                }
                "preference" -> {
                    val pred = facets.firstOrNull { it.startsWith("predicate:") }?.removePrefix("predicate:") ?: continue
                    val obj = facets.firstOrNull { it.startsWith("object:") }?.removePrefix("object:") ?: continue
                    prefBuckets[pred to obj] = (prefBuckets[pred to obj] ?: 0) + seen
                }
                "concern" -> {
                    val topic = facets.firstOrNull { it.startsWith("topic:") }?.removePrefix("topic:") ?: continue
                    concernBuckets[topic] = (concernBuckets[topic] ?: 0) + seen
                }
            }
        }

        return ContactLivePersona(
            nameKey = nameKey,
            bigFive = bigFiveBuckets.entries
                .map { (t, b) -> com.mythara.ui.about.AboutMeViewModel.TraitScore(t, b.score, b.seen) }
                .sortedByDescending { kotlin.math.abs(it.score) },
            values = valueBuckets.entries
                .map { (n, b) -> com.mythara.ui.about.AboutMeViewModel.TraitScore(n, b.score, b.seen) }
                .sortedByDescending { it.score }
                .take(8),
            preferences = prefBuckets.entries
                .map { (k, s) -> com.mythara.ui.about.AboutMeViewModel.PreferenceEntry(k.first, k.second, s) }
                .sortedByDescending { it.seen }
                .take(12),
            concerns = concernBuckets.entries
                .map { (t, s) -> com.mythara.ui.about.AboutMeViewModel.TraitScore(t, s, s) }
                .sortedByDescending { it.score }
                .take(6),
            observations = rows.size,
        )
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastReport = MutableStateFlow<ContactAnalyticsBuilder.BuildReport?>(null)
    val lastReport: StateFlow<ContactAnalyticsBuilder.BuildReport?> = _lastReport.asStateFlow()

    fun rebuild(force: Boolean) {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val report = runCatching { builder.rebuildAll(force = force) }.getOrNull()
            _lastReport.value = report
            _refreshing.value = false
        }
    }

    private val _cleanupStatus = MutableStateFlow<String?>(null)
    val cleanupStatus: StateFlow<String?> = _cleanupStatus.asStateFlow()

    /**
     * Standalone cleanup pass — for the "clean up phantom profiles"
     * button. Useful when the user has just added new aliases and
     * wants the old mis-attributed data gone immediately, without
     * waiting for the next rebuild.
     */
    fun cleanupNow() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val report = runCatching { builder.cleanupAliasMisattributions() }.getOrNull()
            _cleanupStatus.value = if (report == null) "${Glyph.Cross} cleanup failed"
            else "${Glyph.Check} cleaned ${report.cleanedProfiles} phantom profile(s) + ${report.cleanedVaultRows} vault row(s)"
            _refreshing.value = false
        }
    }

    /**
     * Set an app-side avatar override for a contact from a picked
     * image. The image is copied + downscaled into the app's private
     * files dir — the phone's address book is never touched.
     */
    fun setContactPhoto(nameKey: String, src: Uri) {
        viewModelScope.launch {
            val path = ContactPhoto.importOverride(appContext, nameKey, src)
            runCatching { repo.dao.updatePhotoUri(nameKey, path) }
        }
    }

    /** Drop the app-side override; the row falls back to the phone
     *  contact's photo (or the initial-letter avatar). */
    fun clearContactPhoto(nameKey: String) {
        viewModelScope.launch {
            ContactPhoto.clearOverride(appContext, nameKey)
            runCatching { repo.dao.updatePhotoUri(nameKey, null) }
        }
    }
}

/**
 * Top-level analytics screen — list of every contact Mythara has
 * learned about, favorites first, then by most-recent interaction.
 * Tap a row to see the detail (relationship summary + Big Five +
 * notable traits + topics).
 *
 * Refresh button at the bottom triggers a Gemma-backed rebuild of
 * every contact's profile. Cheap aggregation (counts, topics) runs
 * in <100ms over thousands of vault rows; the LLM passes are where
 * the time goes (~2-5s per contact with > 6 messages).
 */
@Composable
fun PeopleScreen(
    onBack: () -> Unit,
    vm: PeopleViewModel = hiltViewModel(),
) {
    val profiles by vm.profiles.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val report by vm.lastReport.collectAsState()
    val cleanupStatus by vm.cleanupStatus.collectAsState()
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selected = profiles.firstOrNull { it.nameKey == selectedKey }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                if (selected != null) selectedKey = null else onBack()
            }) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${Glyph.DiamondFilled} people",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.titleMedium,
            )
            // Refresh lives in the header so it stays visible no matter
            // how long the contact list grows.
            if (selected == null) {
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { vm.rebuild(force = false) },
                    enabled = !refreshing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Fg,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = if (refreshing) "${Glyph.Ellipsis} refreshing" else "${Glyph.Refresh} refresh",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (selected != null) {
            // Refresh the per-contact live-persona snapshot whenever
            // the selection changes. Cheap (single vault scan) and
            // reads stay fresh as turns come in.
            androidx.compose.runtime.LaunchedEffect(selected.nameKey) {
                vm.loadLivePersonaFor(selected.nameKey)
            }
            val livePersona by vm.selectedLivePersona.collectAsState()
            ProfileDetail(
                p = selected,
                livePersona = livePersona.takeIf { it?.nameKey == selected.nameKey },
                onSetPhoto = { uri -> vm.setContactPhoto(selected.nameKey, uri) },
                onClearPhoto = { vm.clearContactPhoto(selected.nameKey) },
            )
        } else {
            ProfileList(
                profiles = profiles,
                onTap = { selectedKey = it.nameKey },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { vm.rebuild(force = true) },
                    enabled = !refreshing,
                ) {
                    Text("force re-infer", color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (refreshing) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MytharaColors.Bok,
                )
            }
            report?.let { r ->
                Spacer(Modifier.height(6.dp))
                val cleanupTail = if (r.cleanedProfiles + r.cleanedVaultRows > 0)
                    " · cleaned ${r.cleanedProfiles} phantom + ${r.cleanedVaultRows} rows"
                else ""
                Text(
                    text = "${Glyph.Check} last refresh: ${r.rebuilt} profiles, ${r.durationMs}ms$cleanupTail",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Dedicated cleanup button — runs ONLY the alias-
            // misattribution sweep, no rebuild. Useful after adding a
            // new alias to quickly purge the phantom profile without
            // a full Gemma pass.
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { vm.cleanupNow() },
                    enabled = !refreshing,
                ) {
                    Text(
                        text = "${Glyph.Cross} clean up phantom profiles (alias matches)",
                        color = MytharaColors.Sriracha,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            cleanupStatus?.let { msg ->
                Text(
                    text = msg,
                    color = if (msg.startsWith(Glyph.Check)) MytharaColors.Julep else MytharaColors.Sriracha,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProfileList(
    profiles: List<ContactProfileRow>,
    onTap: (ContactProfileRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (profiles.isEmpty()) {
        Text(
            text = "${Glyph.CircleOutline} no contacts yet. Mythara learns people from every messaging notification (Teams, WhatsApp, SMS, …) — once a person messages you, they'll show up here automatically.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    // Three-section layout per user spec:
    //   1) Favourites
    //   2) Discovered (the people Mythara has been told about by
    //      cross-app notifications — `is_auto_added` true and not
    //      yet promoted)
    //   3) Everyone else (manually-added / agent-mediated)
    //
    // Sections render in this order so the user can quickly
    // distinguish "people I told the system about" from "people
    // the system found for me".
    val favourites = profiles.filter { it.isFavorite }
    val discovered = profiles.filter { !it.isFavorite && it.isAutoAdded }
    val everyoneElse = profiles.filter { !it.isFavorite && !it.isAutoAdded }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (favourites.isNotEmpty()) {
            item("h-fav") {
                SectionLabel(text = "${Glyph.DiamondFilled} favourites")
            }
            items(favourites, key = { "f-" + it.nameKey }) { p ->
                ProfileRow(p, onTap = { onTap(p) })
            }
        }
        if (discovered.isNotEmpty()) {
            item("h-disc") {
                SectionLabel(
                    text = "${Glyph.CircleOutline} discovered across apps",
                    sub = "auto-added from notifications · tap to confirm or dismiss",
                )
            }
            items(discovered, key = { "d-" + it.nameKey }) { p ->
                ProfileRow(p, onTap = { onTap(p) })
            }
        }
        if (everyoneElse.isNotEmpty()) {
            if (favourites.isNotEmpty() || discovered.isNotEmpty()) {
                item("h-rest") {
                    SectionLabel(text = "${Glyph.AccentBar} everyone else")
                }
            }
            items(everyoneElse, key = { "e-" + it.nameKey }) { p ->
                ProfileRow(p, onTap = { onTap(p) })
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, sub: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
        Text(
            text = text,
            color = MytharaColors.Charple,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        if (sub != null) {
            Text(
                text = sub,
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProfileRow(p: ContactProfileRow, onTap: () -> Unit) {
    val borderColor = if (p.isFavorite) MytharaColors.Charple else MytharaColors.SurfaceHigh
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(if (p.isFavorite) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable { onTap() }
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p)
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = p.displayName,
                            color = MytharaColors.Fg,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (p.isFavorite) {
                            Text(
                                text = "  ${Glyph.DiamondFilled}",
                                color = MytharaColors.Charple,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    val sub = buildString {
                        append("${p.messageCount} interactions")
                        if (p.imageCount > 0) append(" · ${p.imageCount} photos")
                        p.toneLabel?.let { append(" · ").append(it) }
                    }
                    Text(sub, color = MytharaColors.FgDim, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                text = formatRelativeTs(p.lastInteractionMs),
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Cross-app "found in" badge cluster — sourced from
        // [ContactProfileRow.sourceAppsJson] which the
        // CrossAppPersonObserver keeps fresh on every notification.
        // Useful "this is the same human across these apps" cue
        // for the user. Skip when only one app has been seen
        // (uninteresting + uses screen real estate).
        val sourceApps = parseStringList(p.sourceAppsJson)
        if (sourceApps.size > 1) {
            Spacer(Modifier.height(4.dp))
            val labels = sourceApps.take(4).joinToString(" · ") { prettyAppName(it) }
            Text(
                text = "${Glyph.AccentBar} found in: $labels",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Aliases hint — when the same person appears under multiple
        // names across apps, surface that on the row so the user can
        // see the unification at a glance.
        val aliases = parseStringList(p.aliasesJson).filter { !it.equals(p.displayName, ignoreCase = true) }
        if (aliases.isNotEmpty()) {
            Text(
                text = "  also: ${aliases.take(3).joinToString(", ")}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Prefer a single key-point teaser over topics on the row —
        // the user landed on this list to remember WHAT'S HAPPENING
        // before messaging, not browse interests. Falls back to topics
        // when no key points are available.
        val keyPoints = parseStringList(p.keyPointsJson)
        if (keyPoints.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} ${keyPoints.first()}",
                color = MytharaColors.Bok,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            val topics = parseStringList(p.topTopicsJson)
            if (topics.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = topics.joinToString(" · ") { it },
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProfileDetail(
    p: ContactProfileRow,
    /** Per-turn live-persona snapshot for THIS contact, or null when
     *  the extractor hasn't yet logged any contact-targeted rows for
     *  them. Loaded by PeopleScreen's LaunchedEffect on selection. */
    livePersona: PeopleViewModel.ContactLivePersona?,
    onSetPhoto: (Uri) -> Unit,
    onClearPhoto: () -> Unit,
) {
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onSetPhoto(uri) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(p, large = true)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = p.displayName,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.titleLarge,
                )
                if (p.isFavorite) {
                    Text(
                        text = "${Glyph.DiamondFilled} favorite · ${p.toneLabel ?: "realistic"}",
                        color = MytharaColors.Charple,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                p.phone?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MytharaColors.FgDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Photo controls — set an app-only avatar override (or drop it
        // back to the phone contact photo). Never touches the phone's
        // address book.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }) {
                Text(
                    text = "${Glyph.DiamondOutline} ${if (p.photoUri != null) "change photo" else "set photo"}",
                    color = MytharaColors.Bok,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (p.photoUri != null) {
                TextButton(onClick = onClearPhoto) {
                    Text(
                        text = "${Glyph.Cross} remove",
                        color = MytharaColors.Sriracha,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Notes on a contact are intentionally NOT shown here — they
        // live only in the hidden Notes screen (About → triple-tap the
        // wordmark → Notes). They still feed this contact's analysis
        // behind the scenes; this screen just doesn't surface or edit
        // them.
        Spacer(Modifier.height(16.dp))

        // Key points — Gemma-derived "what's happening" prep.
        val keyPoints = parseStringList(p.keyPointsJson)
        if (keyPoints.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MytharaColors.Surface)
                    .border(1.5.dp, MytharaColors.Bok, RoundedCornerShape(10.dp))
                    .padding(14.dp),
            ) {
                Text(
                    text = "${Glyph.DiamondFilled} before you message them",
                    style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.Bok),
                )
                Spacer(Modifier.height(8.dp))
                keyPoints.forEach { point ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${Glyph.AccentBar} ",
                            color = MytharaColors.Bok,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = point,
                            color = MytharaColors.Fg,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        } else if (p.relationshipSummary != null) {
            // Profile exists but no key-points extracted — show a soft
            // hint so the user knows the section can populate.
            Text(
                text = "${Glyph.CircleOutline} no specific prep notes for ${p.displayName} right now — relationship summary below.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        DetailCard("${Glyph.DiamondOutline} stats") {
            val days = ((System.currentTimeMillis() - p.firstSeenMs) / (86_400_000L)).coerceAtLeast(0)
            DetailRow("interactions", "${p.messageCount}")
            DetailRow("photos shared", "${p.imageCount}")
            DetailRow("known for", "$days day${if (days == 1L) "" else "s"}")
            DetailRow("last contact", formatTs(p.lastInteractionMs))
        }

        Spacer(Modifier.height(12.dp))
        DetailCard("${Glyph.DiamondOutline} relationship summary") {
            Text(
                text = p.relationshipSummary
                    ?: "Not enough data yet — Mythara needs a few more conversations before she can describe this relationship.",
                color = if (p.relationshipSummary != null) MytharaColors.Fg else MytharaColors.FgDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        val topics = parseStringList(p.topTopicsJson)
        if (topics.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            DetailCard("${Glyph.DiamondOutline} top topics") {
                Text(
                    text = topics.joinToString(" · "),
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        DetailCard("${Glyph.DiamondOutline} big five — Mythara's read on this person") {
            if (p.openness == null) {
                Text(
                    text = "Big Five estimation needs at least ${ContactProfileRow.MIN_BIG_FIVE_SAMPLE} captured snippets. Currently ${p.messageCount}. Keep chatting — the read sharpens with more samples.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Big5Bar("openness", p.openness)
                Big5Bar("conscientiousness", p.conscientiousness ?: 0.5)
                Big5Bar("extraversion", p.extraversion ?: 0.5)
                Big5Bar("agreeableness", p.agreeableness ?: 0.5)
                Big5Bar("neuroticism", p.neuroticism ?: 0.5)
                p.bigFiveLastUpdatedMs?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${Glyph.AccentBar} estimated from ${p.bigFiveSampleSize} captured snippets · ${formatTs(it)}",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Per-turn-derived live persona for this contact — distinct
        // from the batch big-five above (which comes from analytics
        // worker). Renders the same shape AboutMe uses for self.
        livePersona?.let { lp ->
            Spacer(Modifier.height(12.dp))
            DetailCard("${Glyph.DiamondOutline} live persona · learned from our chats about ${p.displayName}") {
                ContactLivePersonaBody(lp)
            }
        }

        p.personalityInsights?.takeIf { it.isNotBlank() }?.let { insights ->
            Spacer(Modifier.height(12.dp))
            DetailCard("${Glyph.DiamondOutline} personality insights · how to message them") {
                Text(
                    text = insights,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        val traits = parseStringList(p.notableTraitsJson)
        if (traits.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            DetailCard("${Glyph.DiamondOutline} notable traits") {
                Text(
                    text = traits.joinToString(" · "),
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * Body of the per-contact live-persona DetailCard. Reads the same
 * data shape AboutMe uses for self-targeted records and arranges
 * the four sub-sections (big five tendencies, top values, likes/
 * dislikes, on-their-mind) into a compact card.
 *
 * Each sub-section is elided when empty so a contact with only a
 * few observations doesn't render an empty stub.
 */
@Composable
private fun ContactLivePersonaBody(lp: PeopleViewModel.ContactLivePersona) {
    Column {
        if (lp.bigFive.isNotEmpty()) {
            Text(
                text = "${Glyph.DiamondFilled} big five tendencies",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            lp.bigFive.forEach { t ->
                val arrow = when {
                    t.score > 0 -> "↑"
                    t.score < 0 -> "↓"
                    else -> "→"
                }
                val polarity = when {
                    t.score > 0 -> "high"
                    t.score < 0 -> "low"
                    else -> "neutral"
                }
                val color = when {
                    t.score > 0 -> MytharaColors.Bok
                    t.score < 0 -> MytharaColors.Charple
                    else -> MytharaColors.FgMute
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$arrow ${t.name}",
                        color = color,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "$polarity · ${t.seen} obs",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (lp.values.isNotEmpty()) {
            Text(
                "${Glyph.DiamondFilled} top values",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = lp.values.joinToString("  ·  ") { "${it.name} (${it.seen})" },
                color = MytharaColors.Bok,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
        }

        if (lp.preferences.isNotEmpty()) {
            Text(
                "${Glyph.DiamondFilled} likes & dislikes",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            val grouped = lp.preferences.groupBy { it.predicate }
            grouped.forEach { (predicate, entries) ->
                val color = when (predicate) {
                    "likes" -> MytharaColors.Bok
                    "dislikes" -> MytharaColors.Sriracha
                    "wants" -> MytharaColors.Mustard
                    "avoids" -> MytharaColors.FgMute
                    else -> MytharaColors.Fg
                }
                Text(
                    text = "$predicate: " + entries.joinToString(", ") { it.obj },
                    color = color,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        if (lp.concerns.isNotEmpty()) {
            Text(
                "${Glyph.DiamondFilled} on their mind",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = lp.concerns.joinToString("  ·  ") { it.name },
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
        }

        Text(
            "${Glyph.AccentBar} based on ${lp.observations} observations across our chats",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun DetailCard(title: String, body: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))
        body()
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MytharaColors.FgDim, style = MaterialTheme.typography.bodySmall)
        Text(value, color = MytharaColors.Fg, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Big5Bar(label: String, value: Double) {
    val pct = (value.coerceIn(0.0, 1.0) * 100).toInt()
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = MytharaColors.Fg, style = MaterialTheme.typography.bodySmall)
            Text("$pct", color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MytharaColors.Bg),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value.toFloat().coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(big5Color(label, value)),
            )
        }
    }
}

private fun big5Color(label: String, value: Double): Color = when {
    label == "neuroticism" && value > 0.65 -> MytharaColors.Sriracha
    value > 0.65 -> MytharaColors.Bok
    value < 0.35 -> MytharaColors.FgMute
    else -> MytharaColors.Charple
}

/**
 * Contact avatar. Resolves an image via [ContactPhoto] — app-side
 * override first, then the phone contact's photo — and falls back to
 * the initial-letter tile when neither exists.
 */
@Composable
private fun Avatar(profile: ContactProfileRow, large: Boolean = false) {
    val ctx = LocalContext.current
    val side = if (large) 48.dp else 36.dp
    val color = if (profile.isFavorite) MytharaColors.Charple else MytharaColors.SurfaceHigh
    var bitmap by remember(profile.nameKey, profile.photoUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(profile.nameKey, profile.photoUri, profile.phone) {
        bitmap = ContactPhoto.resolveBitmap(ctx, profile)
    }
    Box(
        modifier = Modifier
            .size(side)
            .clip(RoundedCornerShape(side / 2))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = profile.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = profile.displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = MytharaColors.Fg,
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            )
        }
    }
}

private val TIME_FMT = SimpleDateFormat("MMM d, HH:mm", Locale.US)

private fun formatTs(ts: Long): String = TIME_FMT.format(Date(ts))

private fun formatRelativeTs(ts: Long): String {
    val delta = System.currentTimeMillis() - ts
    return when {
        delta < 60_000L -> "just now"
        delta < 3_600_000L -> "${delta / 60_000L}m ago"
        delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
        delta < 30L * 86_400_000L -> "${delta / 86_400_000L}d ago"
        else -> TIME_FMT.format(Date(ts))
    }
}

private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseStringList(s: String): List<String> =
    runCatching { JSON.decodeFromString(ListSerializer(String.serializer()), s) }.getOrDefault(emptyList())

/**
 * Best-effort short label for a notification's source package
 * name. Used by the "found in: …" badge under a People row.
 *
 * Hand-mapped for the most common messaging packages (so we
 * don't need a PackageManager lookup on every paint, and so the
 * label reads as the brand the user knows — "WhatsApp" not
 * "com.whatsapp"). Unknown packages fall back to the last
 * segment of the package name with a capital first letter.
 */
private fun prettyAppName(pkg: String): String = when (pkg) {
    "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
    "com.google.android.apps.messaging" -> "Messages"
    "com.android.mms", "com.samsung.android.messaging" -> "SMS"
    "com.microsoft.teams" -> "Teams"
    "com.Slack", "com.slack" -> "Slack"
    "org.telegram.messenger", "org.telegram.messenger.web" -> "Telegram"
    "org.thoughtcrime.securesms" -> "Signal"
    "com.facebook.orca", "com.facebook.mlite" -> "Messenger"
    "com.discord" -> "Discord"
    "com.skype.raider" -> "Skype"
    "jp.naver.line.android" -> "LINE"
    "com.viber.voip" -> "Viber"
    "com.kakao.talk" -> "KakaoTalk"
    "com.tencent.mm" -> "WeChat"
    "com.google.android.gm" -> "Gmail"
    "ch.protonmail.android" -> "ProtonMail"
    "com.microsoft.office.outlook" -> "Outlook"
    "com.instagram.android" -> "Instagram"
    else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}
