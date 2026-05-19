package com.mythara.people

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.mythara.analytics.ContactProfileRepository
import com.mythara.analytics.ContactProfileRow
import com.mythara.behavior.BehaviorEventStore
import com.mythara.me.MeProfileStore
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import com.mythara.services.NotificationListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that subscribes to [NotificationListener.newNotifications]
 * and turns every messaging-style notification into a People-list
 * row, **across all apps Mythara can see** (Teams, WhatsApp, SMS,
 * Slack, Telegram, Signal, Messenger, Instagram DMs, Discord, etc.).
 *
 * Why this exists:
 *   1. The user explicitly asked for "always learning" — every
 *      person who messages them should show up in People without
 *      requiring an agent-mediated conversation first.
 *   2. Same humans use different display names across apps
 *      ("John S." in Teams, "Johnny" in WhatsApp,
 *      "+15551234567" in SMS). The observer canonicalises by
 *      lowercased trimmed name, falls back to last-7-digit
 *      phone suffix for phone-style senders, and merges aliases
 *      onto the same [ContactProfileRow].
 *   3. The People screen wants a "found in: Teams, WhatsApp"
 *      badge under each row — sourced from the row's
 *      `source_apps_json` field which the observer keeps fresh
 *      every time it sees the same person in a new app.
 *
 * Skip rules:
 *   - Self-notifications (sender matches a [MeProfileStore] alias)
 *     don't create a People row.
 *   - Promo / system notifications (no human sender) are ignored.
 *   - Group-chat messages where the title is a group name not a
 *     person are conservative-skipped via the `:` heuristic
 *     (group messages typically render as "Family Chat: John: hi").
 *   - Empty / null titles ignored.
 *
 * Also writes a behaviour event for every captured exchange — feeds
 * into [BehaviorEventStore] so the daily summariser can derive
 * patterns like "messages from $name peak after 9pm" or "haven't
 * heard from $name in 3 weeks".
 *
 * Bound from `MytharaApp.onCreate` so it runs for the whole process
 * lifetime, independent of which UI is foreground.
 */
@Singleton
class CrossAppPersonObserver @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: ContactProfileRepository,
    private val meProfile: MeProfileStore,
    private val vault: LearningVault,
    private val entityClassifier: com.mythara.analytics.EntityKindClassifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var subscription: Job? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val stringList = ListSerializer(String.serializer())

    fun start() {
        if (subscription?.isActive == true) return
        subscription = scope.launch {
            NotificationListener.newNotifications.collect { recent ->
                runCatching { ingest(recent) }
                    .onFailure { Log.w(TAG, "ingest failed: ${it.message}") }
            }
        }
        Log.i(TAG, "started — listening for cross-app person events")
    }

    fun stop() {
        subscription?.cancel()
        subscription = null
    }

    private suspend fun ingest(r: NotificationListener.Recent) {
        // Extract a sender name. The notification's title is the
        // canonical Android channel for "who is this from" — Teams,
        // WhatsApp, SMS all set it. Group-chat semantics: WhatsApp
        // sets title="GroupName" with text="John: hi"; we pull
        // John out of the body when the title looks like a group
        // (no body colon-prefix → not a group; has prefix → group).
        if (r.ongoing || r.looksLikeCall || r.looksLikeVideo) return
        val (senderName, isGroup) = extractSender(r) ?: return
        if (senderName.isBlank()) return

        // Skip self.
        if (meProfile.matchesSelf(senderName)) {
            Log.d(TAG, "skip self-notif from $senderName")
            return
        }

        // Classify FIRST so we can route non-persons into the
        // hidden bucket instead of dropping them on the floor.
        // EntityKindClassifier replaces the old single-tier
        // looksLikeBrandSender gate — it produces a verdict
        // (kind + confidence + reason) we persist on the row so
        // the People-screen filter, Insights node colour, and the
        // Hidden-Rows sub-screen all agree.
        // Canonicalise BEFORE classifying so the classifier's
        // user-override gate gets a chance to win. Without the
        // nameKey, the override store can't be consulted and the
        // user's "always classify <X> as organization" pin would
        // be silently ignored every time a fresh notification from
        // that sender arrives.
        val nameKey = canonicalizeName(senderName)
        if (nameKey.isBlank()) return

        val verdict = entityClassifier.classifyIncoming(
            senderName = senderName,
            packageName = r.packageName,
            nameKey = nameKey,
        )
        val hideOnInsert = verdict.kind != ContactProfileRow.KIND_PERSON

        val now = System.currentTimeMillis()
        // Direct key match first; if that misses, scan the aliases
        // column so a notification from "Rose" merges into the
        // contact whose canonical name_key is `roselyn-mathew` but
        // who has "Rose" in their user-curated alias list. Falling
        // back to alias lookup before creating a brand-new row is
        // what makes user-added aliases functionally useful across
        // apps.
        val existing = repo.dao.byKey(nameKey)
            ?: findExistingByAlias(senderName)
        val appLabel = appLabelFor(r.packageName)

        if (existing == null) {
            // New person — create a baseline auto-added row.
            val row = ContactProfileRow(
                nameKey = nameKey,
                displayName = senderName.trim().take(80),
                phone = null,
                isFavorite = false,
                firstSeenMs = now,
                lastInteractionMs = now,
                messageCount = 1,
                aliasesJson = json.encodeToString(stringList, listOf(senderName.trim())),
                sourceAppsJson = json.encodeToString(stringList, listOf(r.packageName)),
                isAutoAdded = true,
                lastBuiltMs = 0L,
                kind = verdict.kind,
                kindConfidence = verdict.confidence,
                kindClassifiedAtMs = now,
                isHidden = hideOnInsert,
            )
            repo.dao.upsert(row)
            Log.i(
                TAG,
                "auto-added '$senderName' from ${r.packageName} ($appLabel) " +
                    "kind=${verdict.kind} hidden=$hideOnInsert (${verdict.reason})",
            )
        } else {
            // Same canonical key — merge alias + source-app and bump counts.
            // We DON'T overwrite kind/hidden on merge: once the user has
            // explicitly restored a row (kind=person, hidden=false), a
            // later spammy notification mustn't re-demote it. The cleanup
            // runner is the canonical retag path for existing rows.
            val aliases = decodeList(existing.aliasesJson).toMutableSet()
            val rawName = senderName.trim()
            if (aliases.none { it.equals(rawName, ignoreCase = true) }) aliases += rawName
            val apps = decodeList(existing.sourceAppsJson).toMutableSet()
            apps += r.packageName
            repo.dao.upsert(
                existing.copy(
                    lastInteractionMs = now,
                    messageCount = existing.messageCount + 1,
                    aliasesJson = json.encodeToString(stringList, aliases.toList()),
                    sourceAppsJson = json.encodeToString(stringList, apps.toList()),
                ),
            )
        }

        // Behaviour-event side-channel — the daily summariser uses
        // these to spot patterns ("first contact from X today",
        // "8 messages from Y between 11pm and 1am"). Tagged with
        // [BehaviorEventStore.FACET_KIND] so the daily summary
        // worker picks them up + then deletes the raw rows after
        // synthesis. ALSO tagged with `contact:<key>` so the
        // contact-analytics builder finds them when rebuilding
        // this person's profile.
        val body = (r.text ?: "").take(200)
        // Prefer the canonical nameKey we ended up writing to on the
        // contact row — that way an alias-matched merge stamps the
        // behaviour-event with the same key the analytics builder
        // later queries by.
        val canonicalKey = existing?.nameKey ?: nameKey
        runCatching {
            vault.add(
                content = "[$appLabel] $senderName: $body",
                tier = Tier.Working,
                src = "behavior:cross-app-msg",
                facets = listOf(
                    BehaviorEventStore.FACET_KIND,
                    "behavior:cross-app-msg",
                    "contact:$canonicalKey",
                    "app:${r.packageName}",
                    if (isGroup) "channel:group" else "channel:direct",
                ),
                conf = 0.85,
            )
        }
    }

    /** Scan the contact-profile aliases column for a row whose
     *  alias list contains [senderName] (case-insensitive). Returns
     *  the strongest candidate (highest messageCount) or null when
     *  no alias matches. Bounded by the DAO's LIMIT 10 + an in-
     *  memory exact-match filter so a "Sam" notification doesn't
     *  spuriously merge into a contact whose alias contains
     *  "Sample" or "Samuel". */
    private suspend fun findExistingByAlias(senderName: String): ContactProfileRow? {
        val trimmed = senderName.trim()
        if (trimmed.length < 2) return null
        val candidates = runCatching {
            // The LIKE substring search is cheap; we re-verify the
            // exact alias in JVM so partial-word collisions don't
            // bleed across contacts.
            repo.dao.findByAliasContaining(trimmed)
        }.getOrDefault(emptyList())
        if (candidates.isEmpty()) return null
        for (row in candidates) {
            val aliases = runCatching { decodeList(row.aliasesJson) }.getOrDefault(emptyList())
            val hit = aliases.any { it.trim().equals(trimmed, ignoreCase = true) }
            if (hit) return row
        }
        return null
    }

    /**
     * Pull the human sender name out of a notification. Returns
     * `(name, isGroupContext)` — the latter is a hint for the
     * caller to keep group-vs-direct context in the behaviour
     * event.
     *
     * Heuristic ordering:
     *   1. Title alone is the sender if there's no `:` in the body
     *      (single-DM case for almost all messengers).
     *   2. If body starts with `Name: …`, the body's prefix is the
     *      real sender (group-chat case). Title becomes the group
     *      and the actual sender is the body prefix.
     *   3. SMS uses the title for the contact name OR a phone
     *      number — both are usable.
     */
    private fun extractSender(r: NotificationListener.Recent): Pair<String, Boolean>? {
        val title = r.title?.trim() ?: return null
        if (title.isBlank()) return null
        val text = r.text.orEmpty()
        // Group-chat shape: body starts with "Name: …".
        val groupMatch = GROUP_PREFIX_REGEX.find(text)
        return if (groupMatch != null) {
            val sender = groupMatch.groupValues[1].trim()
            if (sender.isBlank() || sender.length > 60) null
            else sender to true
        } else {
            title to false
        }
    }

    /**
     * Brand-sender heuristic. Anything that looks like a service
     * notification ("Bank of X", "Uber", "DoorDash", etc.) shouldn't
     * become a People row. Conservative: package-allowlist for
     * actual messenger apps; default-deny for unknown packages.
     *
     * The intent is "always learn from MESSAGING apps", not "every
     * notification ever" — promo notifications are filtered out
     * upstream by `PromoNotificationClassifier` but service-style
     * notifications can still slip through (a bank-app push isn't
     * promo, it's transactional, but the sender isn't a person).
     */
    private fun looksLikeBrandSender(senderName: String, pkg: String): Boolean {
        if (pkg in MESSENGER_APP_PACKAGES) return false
        // Unknown package — only accept if the title looks like a
        // person's name (alphabetic, ≤4 words). Skip ALL-CAPS,
        // skip names ending in 'Inc'/'Ltd'/'Bot', skip bare
        // numbers without country code.
        val parts = senderName.split(' ').filter { it.isNotBlank() }
        if (parts.size > 4) return true
        if (senderName.uppercase() == senderName && senderName.length > 4) return true
        if (BRAND_SUFFIXES.any { senderName.endsWith(it, ignoreCase = true) }) return true
        return false
    }

    /** Canonicalise a sender to a Room PK. Lowercased + non-letter-
     *  collapsed; phone-style (digit-heavy) senders use the digit-
     *  only suffix to dedupe formattings ("+1 (555)" vs "5551234567"). */
    private fun canonicalizeName(name: String): String {
        val trimmed = name.trim()
        // Phone-style sender? (≥6 digits, mostly digits/punct)
        val digits = trimmed.filter { it.isDigit() }
        val isPhoneish = digits.length >= 6 &&
            trimmed.count { it.isLetter() } < trimmed.count { it.isDigit() }
        return if (isPhoneish) {
            "phone:" + digits.takeLast(7)
        } else {
            trimmed.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        }
    }

    private fun decodeList(jsonRaw: String): List<String> = runCatching {
        json.decodeFromString(stringList, jsonRaw)
    }.getOrDefault(emptyList())

    private fun appLabelFor(pkg: String): String = runCatching {
        val pm = ctx.packageManager
        val app = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(app).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val TAG = "Mythara/CrossAppPeople"

        /** Body shapes like "John: hello" / "John Smith: hey" — the
         *  first colon-prefixed segment is the real sender in a
         *  group-chat notification. */
        private val GROUP_PREFIX_REGEX = Regex("^([A-Za-z][^:]{0,58}):\\s")

        /** Known messenger package allowlist. We accept ALL senders
         *  from these packages without further filtering — they're
         *  by definition person-to-person channels. Add new
         *  messengers as they appear in the field. */
        private val MESSENGER_APP_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",   // Google Messages (RCS + SMS)
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.microsoft.teams",
            "com.Slack",
            "com.slack",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thoughtcrime.securesms",          // Signal
            "com.facebook.orca",                   // Messenger
            "com.facebook.mlite",                  // Messenger Lite
            "com.discord",
            "com.skype.raider",
            "jp.naver.line.android",               // LINE
            "com.viber.voip",
            "com.kakao.talk",
            "com.tencent.mm",                      // WeChat
            "com.google.android.gm",               // Gmail
            "ch.protonmail.android",
            "com.microsoft.office.outlook",
            "com.instagram.android",
        )

        /** Suffixes that strongly indicate a brand / bot rather than
         *  a person. Conservative — false positives just exclude a
         *  human; false negatives create a one-off People row. */
        private val BRAND_SUFFIXES = setOf(
            " Inc", " Ltd", " LLC", " GmbH", " Corp",
            " Bot", "-bot", " Notifications", " Updates",
            " Alerts", " Support", " Team",
        )
    }
}
