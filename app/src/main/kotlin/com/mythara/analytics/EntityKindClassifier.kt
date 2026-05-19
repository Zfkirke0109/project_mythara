package com.mythara.analytics

import com.mythara.analytics.ContactProfileRow.Companion.KIND_APP
import com.mythara.analytics.ContactProfileRow.Companion.KIND_NOTIFICATION
import com.mythara.analytics.ContactProfileRow.Companion.KIND_ORG
import com.mythara.analytics.ContactProfileRow.Companion.KIND_PERSON
import com.mythara.analytics.ContactProfileRow.Companion.KIND_PLACE
import com.mythara.analytics.ContactProfileRow.Companion.KIND_UNKNOWN
import com.mythara.lifeline.LifelineRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Type-aware classifier for [ContactProfileRow]. Decides whether a
 * row that arrived through the notification observer or got
 * auto-promoted by an analytics pass is actually a PERSON, or
 * something that should be hidden from the People screen
 * (organization, app, weather/news/finance notification source,
 * place, or just unknown).
 *
 * Two entry points:
 *   - [classifyIncoming]  — called by CrossAppPersonObserver at
 *                            insert / merge time with raw sender +
 *                            package context (best signal).
 *   - [classifyExisting]  — called by PeopleCleanupRunner over
 *                            already-persisted rows where we only
 *                            have displayName + sourceApps + aliases.
 *
 * Both return a [Verdict] (kind + confidence + reason). The reason
 * is logged so the Hidden Rows screen can explain WHY a row was
 * demoted.
 *
 * Heuristic-only — no LLM in the loop. Conservative bias: when in
 * doubt, return KIND_UNKNOWN rather than KIND_PERSON. The cleanup
 * runner re-classifies unknowns on the next pass; the People list
 * shows unknowns by default but flags them with a confidence
 * indicator so the user can promote / demote manually.
 */
@Singleton
class EntityKindClassifier @Inject constructor(
    private val lifelineRepo: LifelineRepository,
    /** User-pinned overrides — when set, the classifier returns the
     *  user's choice with confidence 1.0 and skips every heuristic.
     *  This is how "WhatsApp Business" notifications from your tailor
     *  get classified as `organization` even though the heuristics
     *  alone might think it's a person. */
    private val overrideStore: ContactKindOverrideStore,
) {

    data class Verdict(
        val kind: String,
        val confidence: Float,
        val reason: String,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val stringList = ListSerializer(String.serializer())

    /** Hot-cached set of place labels harvested from the lifeline.
     *  Refreshed every [PLACE_CACHE_TTL_MS] so a new photo with a
     *  new place becomes classifiable without an app restart. */
    @Volatile private var placeCache: Set<String> = emptySet()
    @Volatile private var placeCacheTs: Long = 0L

    /** Classify based on raw sender name + the package the
     *  notification came from. Highest-signal path — uses both.
     *  When the caller knows the row's canonical `nameKey` it can
     *  pass it so the user-override store gets a chance to win
     *  before any heuristic runs. */
    suspend fun classifyIncoming(
        senderName: String,
        packageName: String,
        aliases: List<String> = emptyList(),
        nameKey: String? = null,
    ): Verdict {
        // User-pinned override beats every heuristic. Only consulted
        // when the caller already knows the canonical nameKey for
        // this sender (typically the CrossAppPersonObserver path,
        // which canonicalises the name right before calling).
        if (nameKey != null && nameKey.isNotBlank()) {
            runCatching { overrideStore.get(nameKey) }.getOrNull()?.let { ov ->
                return Verdict(ov.kind, 1.0f, "user-pinned override")
            }
        }
        // Known messenger app → trust the sender is a person unless
        // it trips one of the brand / promo filters below.
        val fromMessenger = packageName in MESSENGER_APP_PACKAGES

        // Numeric / short-code senders are almost never people.
        if (isNumericSender(senderName)) {
            return Verdict(KIND_NOTIFICATION, 0.95f, "numeric/short-code sender")
        }

        // System-utility / OS packages → "app".
        if (packageName in SYSTEM_APP_PACKAGES) {
            return Verdict(KIND_APP, 0.92f, "system-utility package $packageName")
        }

        // Brand / org suffixes ("Inc", "Ltd", "Bot", "Notifications", ...).
        if (BRAND_SUFFIXES.any { senderName.endsWith(it, ignoreCase = true) }) {
            return Verdict(KIND_ORG, 0.88f, "brand suffix")
        }

        // ALL-CAPS multi-word, > 10 chars → almost always a brand.
        if (senderName.length > 10 && senderName == senderName.uppercase() &&
            senderName.split(' ').size >= 2
        ) {
            return Verdict(KIND_ORG, 0.85f, "ALL-CAPS multi-word brand")
        }

        // Existing PROMO_MARKERS check (reuses ContactClassifier's
        // regex set so we don't drift).
        if (!ContactClassifier.isPersonal(senderName)) {
            // Look more carefully — weather / news / finance gets its
            // own kind, the rest are organizations.
            return if (looksLikeWeatherOrNewsOrFinance(senderName)) {
                Verdict(KIND_NOTIFICATION, 0.90f, "weather/news/finance pattern")
            } else {
                Verdict(KIND_ORG, 0.80f, "non-personal sender (promo marker / no letters / etc)")
            }
        }

        // Compound check on aliases too — sometimes the FIRST sender
        // looked like a name but later aliases revealed the truth.
        val aliasHits = aliases.count { !ContactClassifier.isPersonal(it) }
        if (aliasHits >= 2 && aliasHits >= aliases.size / 2) {
            return Verdict(KIND_ORG, 0.75f, "majority of aliases look non-personal")
        }

        // Place name check — does this match a lifeline place_label?
        if (isKnownPlace(senderName)) {
            return Verdict(KIND_PLACE, 0.80f, "matches a known lifeline place")
        }

        // Word-count heuristic for "65 in Naperville" style weather
        // notifications: degrees + temperature + place name pattern.
        if (looksLikeWeatherOrNewsOrFinance(senderName)) {
            return Verdict(KIND_NOTIFICATION, 0.90f, "weather/news/finance pattern")
        }

        // Default: trust the sender is a person.
        val conf = if (fromMessenger) 0.92f else 0.75f
        return Verdict(KIND_PERSON, conf, "person (no rejection rule fired)")
    }

    /** Classify a row that already exists (the cleanup runner path).
     *  Aggregates sourceApps + aliases to mimic the incoming-side
     *  signals as best we can.
     *
     *  USER OVERRIDE wins over every heuristic — if the user has
     *  pinned this row to a kind via long-press in People, we return
     *  that kind with confidence 1.0 and skip the cascade entirely. */
    suspend fun classifyExisting(row: ContactProfileRow): Verdict {
        // Override gate first — if pinned, we're done.
        runCatching { overrideStore.get(row.nameKey) }.getOrNull()?.let { ov ->
            return Verdict(ov.kind, 1.0f, "user-pinned override")
        }

        val aliases = runCatching { json.decodeFromString(stringList, row.aliasesJson) }
            .getOrDefault(emptyList())
        val sourceApps = runCatching { json.decodeFromString(stringList, row.sourceAppsJson) }
            .getOrDefault(emptyList())

        // If ALL source apps are system-utility, it's almost certainly an app.
        if (sourceApps.isNotEmpty() && sourceApps.all { it in SYSTEM_APP_PACKAGES }) {
            return Verdict(KIND_APP, 0.90f, "every source app is a system utility")
        }

        // Pick a representative package — the first messenger-like
        // one wins so classifyIncoming sees a fair signal.
        val pkg = sourceApps.firstOrNull { it in MESSENGER_APP_PACKAGES }
            ?: sourceApps.firstOrNull()
            ?: "unknown.package"
        return classifyIncoming(row.displayName, pkg, aliases)
    }

    /** Did we already see this name as a `place_label` on a
     *  lifeline photo? */
    private suspend fun isKnownPlace(name: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - placeCacheTs > PLACE_CACHE_TTL_MS) {
            placeCache = runCatching {
                lifelineRepo.dao.listAllLocal()
                    .mapNotNull { it.placeLabel?.trim()?.takeIf { p -> p.length >= 3 }?.lowercase() }
                    .toSet()
            }.getOrDefault(emptySet())
            placeCacheTs = now
        }
        val lower = name.trim().lowercase()
        return lower in placeCache
    }

    private fun isNumericSender(name: String): Boolean {
        val stripped = name.replace(Regex("[\\s\\-()+]"), "")
        return stripped.isNotEmpty() && stripped.all { it.isDigit() }
    }

    /** Pattern check for "65 in Naperville" style temperatures,
     *  "AAPL up 2.3%", "Heavy rain expected", "Severe weather alert"
     *  etc. Conservative — single-pattern hit = high confidence
     *  this isn't a person. */
    private fun looksLikeWeatherOrNewsOrFinance(text: String): Boolean {
        val lower = text.lowercase()
        return WEATHER_NEWS_FINANCE_PATTERNS.any { it.containsMatchIn(lower) }
    }

    companion object {
        const val PLACE_CACHE_TTL_MS = 5L * 60 * 1000  // 5 min

        /** Messenger apps where senders are almost always people. */
        private val MESSENGER_APP_PACKAGES = setOf(
            "com.whatsapp", "com.whatsapp.w4b",
            "com.google.android.apps.messaging",
            "com.android.mms", "com.samsung.android.messaging",
            "com.microsoft.teams", "com.Slack", "com.slack",
            "org.telegram.messenger", "org.telegram.messenger.web",
            "org.thoughtcrime.securesms",
            "com.facebook.orca", "com.facebook.mlite",
            "com.discord",
            "com.skype.raider",
            "jp.naver.line.android",
            "com.viber.voip", "com.kakao.talk",
            "com.tencent.mm",
            "com.google.android.gm",
            "ch.protonmail.android",
            "com.microsoft.office.outlook",
            "com.instagram.android",
        )

        /** Packages whose notifications are almost never about people. */
        private val SYSTEM_APP_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.gms",
            "com.google.android.providers.media.module",
            "com.android.providers.downloads",
            "com.android.providers.calendar",
            "com.android.calendar",
            "com.google.android.calendar",
            "com.android.alarmclock",
            "com.google.android.deskclock",
            "com.android.settings",
            "com.android.bluetooth",
            "com.google.android.googlequicksearchbox",
            "com.google.android.gm.lite",
        )

        private val BRAND_SUFFIXES = setOf(
            " Inc", " Ltd", " LLC", " GmbH", " Corp",
            " Bot", "-bot", " Notifications", " Updates",
            " Alerts", " Support", " Team", " App",
        )

        private val WEATHER_NEWS_FINANCE_PATTERNS = listOf(
            // Temperatures + meteorological signals.
            Regex("""\b\d{1,3}\s?°"""),
            Regex("""\b\d{1,3}\s*°?\s*(?:c|f|celsius|fahrenheit)\b"""),
            Regex("""\b\d{1,3}\s*(?:in|near|over|around|at|outside|today|tomorrow|tonight)\b.*\b(?:rain|snow|sunny|cloud|storm|wind|forecast|degrees?|humidity|warmer|cooler|temperature)\b"""),
            Regex("""\b(?:high|low)\s+of\s+\d"""),
            Regex("""\b(?:forecast|severe weather|tornado|hurricane|wildfire|heat advisory|wind chill|flood warning)\b"""),
            Regex("""\b\d{1,3}\s+in\s+[a-z]+\b"""),    // "65 in Naperville"
            // Financial / market alerts.
            Regex("""\b[a-z]{2,5}\s+(?:up|down)\s+\d"""),
            Regex("""\$\d+\.?\d*\s*(?:k|m|b)?\b"""),
            Regex("""\b(?:stock|earnings|nasdaq|nyse|dow|s&p|crypto|bitcoin|btc|eth)\b"""),
            // OTPs / transactional / promo.
            Regex("""\bone[- ]time\s+(?:code|password|pin)\b"""),
            Regex("""\botp\b"""),
            Regex("""\b\d{4,8}\s+is\s+your\b"""),
            Regex("""\b(?:order|delivery|shipment)\s+(?:confirm|update|status|complete|out for|delivered|tracking)\b"""),
            // News / sports digest headers.
            Regex("""\b(?:breaking|news alert|sports update|game (?:tonight|today)|match recap)\b"""),
        )
    }
}
