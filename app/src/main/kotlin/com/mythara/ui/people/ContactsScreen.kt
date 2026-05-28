package com.mythara.ui.people

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.analytics.ContactProfileRepository
import com.mythara.data.FavoritesStore
import com.mythara.people.ContactActions
import com.mythara.people.SystemContactsRepository
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A system address-book contact merged with Mythara's
 *  interaction-frequency signal + favourite flag. */
data class MergedContact(
    val displayName: String,
    val primaryPhone: String?,
    val photoUri: String?,
    val hasWhatsApp: Boolean,
    val isFavorite: Boolean,
    val messageCount: Int,
    val lastInteractionMs: Long?,
)

data class ContactSections(
    val favorites: List<MergedContact>,
    val frequent: List<MergedContact>,
    val all: List<MergedContact>,
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val systemContacts: SystemContactsRepository,
    private val profiles: ContactProfileRepository,
    private val favoritesStore: FavoritesStore,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _hasPerm = MutableStateFlow(systemContacts.hasPermission())
    val hasPerm: StateFlow<Boolean> = _hasPerm.asStateFlow()

    private val _all = MutableStateFlow<List<MergedContact>>(emptyList())

    /** Sectioned contact list, filtered by [query]. */
    val sections: StateFlow<ContactSections> =
        combine(_all, _query) { all, q ->
            val ql = q.trim().lowercase()
            val filtered = if (ql.isEmpty()) all else all.filter {
                it.displayName.lowercase().contains(ql) ||
                    it.primaryPhone?.contains(ql) == true
            }
            val favs = filtered.filter { it.isFavorite }
                .sortedBy { it.displayName.lowercase() }
            val freq = filtered
                .filterNot { it.isFavorite }
                .filter { it.messageCount > 0 || it.lastInteractionMs != null }
                .sortedWith(
                    compareByDescending<MergedContact> { it.messageCount }
                        .thenByDescending { it.lastInteractionMs ?: 0L },
                )
                .take(8)
            val freqKeys = freq.map { it.displayName.lowercase() }.toSet()
            val rest = filtered
                .filterNot { it.isFavorite }
                .filterNot { freqKeys.contains(it.displayName.lowercase()) }
                .sortedBy { it.displayName.lowercase() }
            ContactSections(favorites = favs, frequent = freq, all = rest)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ContactSections(emptyList(), emptyList(), emptyList()),
        )

    init { refresh() }

    fun setQuery(q: String) { _query.value = q }

    fun refresh() {
        _hasPerm.value = systemContacts.hasPermission()
        viewModelScope.launch {
            val sys = systemContacts.loadAll()
            val rows = profiles.dao.observeAll().first()
            val byKey = rows.associateBy { it.nameKey }
            val favs = favoritesStore.favoritesFlow().first()
            val favNames = favs.map { it.name.lowercase().trim() }.toSet()
            _all.value = sys.map { sc ->
                val nk = sc.displayName.lowercase().trim()
                val row = byKey[nk]
                MergedContact(
                    displayName = sc.displayName,
                    primaryPhone = sc.primaryPhone,
                    photoUri = sc.photoUri,
                    hasWhatsApp = sc.hasWhatsApp,
                    isFavorite = nk in favNames || (row?.isFavorite == true),
                    messageCount = row?.messageCount ?: 0,
                    lastInteractionMs = row?.lastInteractionMs,
                )
            }
        }
    }
}

/**
 * Contacts-style People screen (v7 P4). Reads the full system address
 * book via [SystemContactsRepository] and surfaces three sections —
 * favourites, frequently contacted, and all (A–Z) — with a top search
 * bar and per-contact direct call / SMS / WhatsApp actions.
 */
@Composable
fun ContactsScreen(
    vm: ContactsViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val sections by vm.sections.collectAsState()
    val query by vm.query.collectAsState()
    val hasPerm by vm.hasPerm.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.refresh() }

    DisposableEffect(Unit) {
        // Re-check permission + reload on every entry (e.g. after the
        // user grants the permission via Settings).
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.refresh()
        else permLauncher.launch(Manifest.permission.READ_CONTACTS)
        onDispose { }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            value = query,
            onValueChange = vm::setQuery,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
        if (!hasPerm) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${Glyph.AccentBar} contacts permission needed",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${Glyph.Arrow} grant",
                        color = MytharaColors.Charple,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MytharaColors.Charple.copy(alpha = 0.18f))
                            .clickable { permLauncher.launch(Manifest.permission.READ_CONTACTS) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // NOTE: no `key =` on these items() — the address book can
            // legitimately contain multiple contacts with the same
            // displayName (merged contacts, duplicates) and Compose
            // crashes hard if a key collides. Position-based keys are
            // fine here: this list re-snapshots on refresh, no
            // animated reorder.
            if (sections.favorites.isNotEmpty()) {
                item("h:favs") { SectionHeader("◆ favourites") }
                items(sections.favorites) { c -> ContactRow(c = c, ctx = ctx) }
            }
            if (sections.frequent.isNotEmpty()) {
                item("h:freq") { SectionHeader("● frequently contacted") }
                items(sections.frequent) { c -> ContactRow(c = c, ctx = ctx) }
            }
            if (sections.all.isNotEmpty()) {
                item("h:all") { SectionHeader("◇ all contacts") }
                items(sections.all) { c -> ContactRow(c = c, ctx = ctx) }
            }
            if (sections.favorites.isEmpty() && sections.frequent.isEmpty() && sections.all.isEmpty()) {
                item("empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (query.isBlank()) "no contacts" else "no match for \"$query\"",
                            color = MytharaColors.FgMute,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        color = MytharaColors.FgMute,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MytharaColors.SurfaceMid.copy(alpha = 0.6f))
            .border(1.dp, MytharaColors.SurfaceHigh.copy(alpha = 0.6f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${Glyph.DiamondOutline}",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.isEmpty()) {
                    Text(
                        text = "search contacts…",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(
                        color = MytharaColors.Fg,
                        fontSize = 16.sp,
                    ),
                    cursorBrush = SolidColor(MytharaColors.Charple),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ContactRow(c: MergedContact, ctx: android.content.Context) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(MytharaColors.Surface.copy(alpha = 0.55f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = c.displayName)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.fillMaxWidth().padding(end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = c.displayName,
                        color = MytharaColors.Fg,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                    if (c.isFavorite) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "★",
                            color = MytharaColors.Mustard,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (c.hasWhatsApp) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "wa",
                            color = MytharaColors.Bok,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MytharaColors.Bok.copy(alpha = 0.18f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                c.primaryPhone?.let {
                    Text(
                        text = it,
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 56.dp),
            ) {
                c.primaryPhone?.let { num ->
                    ContactActionChip(
                        label = "📞 call",
                        color = MytharaColors.Bok,
                        onTap = { ContactActions.phoneCall(ctx, num) },
                    )
                    ContactActionChip(
                        label = "✉ sms",
                        color = MytharaColors.Malibu,
                        onTap = { ContactActions.sms(ctx, num) },
                    )
                    if (c.hasWhatsApp) {
                        ContactActionChip(
                            label = "wa chat",
                            color = MytharaColors.Charple,
                            onTap = { ContactActions.whatsAppChat(ctx, num) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Avatar(name: String) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MytharaColors.Charple.copy(alpha = 0.32f))
            .border(1.dp, MytharaColors.Charple.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun ContactActionChip(label: String, color: androidx.compose.ui.graphics.Color, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}
