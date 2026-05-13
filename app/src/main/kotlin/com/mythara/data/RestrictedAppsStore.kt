package com.mythara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two policy lists for app-level guardrails on agent automation:
 *
 *   • BLOCKED — banking, payment, wallet, brokerage apps. The agent
 *     refuses any side-effect tool whose target is one of these
 *     packages. open_app fails. tap/swipe/type_text fail when one of
 *     these apps is in the foreground. No autopilot toggle, no
 *     allowlist override, no "always allow" path — this is a hard
 *     veto. The cost of a wrong tap inside a banking app (transfer,
 *     payment, account closure) is high enough that we never accept
 *     it without a real human tap.
 *
 *   • CRITICAL — ride-hailing, food delivery, e-commerce, ticketing.
 *     These ARE allowed but always require an explicit confirmation
 *     prompt before any side-effect tool fires, regardless of the
 *     user's global "always confirm" toggle or the per-call allowlist.
 *     Booking an Uber, placing a DoorDash order, buying something on
 *     Amazon — all destructive, all expensive if wrong, all worth
 *     the one extra tap.
 *
 * Both lists are seeded with sensible defaults and persist a
 * user-added overlay in DataStore so power users can add more
 * (e.g. a regional bank we don't ship a default for) without code
 * changes.
 *
 * Read tools (list_calendar_events, read_screen, read_contact, etc.)
 * are NEVER gated by these lists. The guard is strictly about
 * SIDE-EFFECT tools — anything that drives the UI, sends, or opens.
 */
@Singleton
class RestrictedAppsStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_restricted_apps")

    private val keyBlockedExtras = stringSetPreferencesKey("blocked.extra")
    private val keyCriticalExtras = stringSetPreferencesKey("critical.extra")

    fun blockedFlow(): Flow<Set<String>> = ctx.dataStore.data.map { prefs ->
        BLOCKED_DEFAULTS + (prefs[keyBlockedExtras] ?: emptySet())
    }

    fun criticalFlow(): Flow<Set<String>> = ctx.dataStore.data.map { prefs ->
        CRITICAL_DEFAULTS + (prefs[keyCriticalExtras] ?: emptySet())
    }

    fun blockedExtrasFlow(): Flow<Set<String>> =
        ctx.dataStore.data.map { it[keyBlockedExtras] ?: emptySet() }

    fun criticalExtrasFlow(): Flow<Set<String>> =
        ctx.dataStore.data.map { it[keyCriticalExtras] ?: emptySet() }

    suspend fun isBlocked(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return pkg in blockedFlow().first()
    }

    suspend fun isCritical(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return pkg in criticalFlow().first()
    }

    suspend fun addBlocked(pkg: String) {
        val p = pkg.trim()
        if (p.isBlank()) return
        ctx.dataStore.edit { prefs ->
            val cur = prefs[keyBlockedExtras] ?: emptySet()
            prefs[keyBlockedExtras] = cur + p
        }
    }

    suspend fun removeBlocked(pkg: String) {
        ctx.dataStore.edit { prefs ->
            val cur = prefs[keyBlockedExtras] ?: emptySet()
            prefs[keyBlockedExtras] = cur - pkg
        }
    }

    suspend fun addCritical(pkg: String) {
        val p = pkg.trim()
        if (p.isBlank()) return
        ctx.dataStore.edit { prefs ->
            val cur = prefs[keyCriticalExtras] ?: emptySet()
            prefs[keyCriticalExtras] = cur + p
        }
    }

    suspend fun removeCritical(pkg: String) {
        ctx.dataStore.edit { prefs ->
            val cur = prefs[keyCriticalExtras] ?: emptySet()
            prefs[keyCriticalExtras] = cur - pkg
        }
    }

    companion object {
        /**
         * Banking + payment + wallet + brokerage defaults. NOT
         * exhaustive — meant to cover the majority of US/EU/IN users
         * out of the box. Users add their own regional bank via the
         * Settings panel.
         */
        val BLOCKED_DEFAULTS: Set<String> = setOf(
            // US banks
            "com.chase.sig.android",
            "com.bofa.ecom.android",
            "com.usaa.mobile.android.usaa",
            "com.wf.wellsfargomobile",
            "com.citi.citimobile",
            "com.discoverfinancial.mobile",
            "com.americanexpress.android.acctsvcs.us",
            "com.capitalone.mobile.activity",
            "com.pnc.ecommerce.mobile",
            "com.tdbank",
            "com.fidelity.android",
            "com.schwab.mobile",
            "com.vanguard.mobile",
            "com.robinhood.android",
            "com.coinbase.android",
            "com.binance.dev",
            // Payment / wallet
            "com.paypal.android.p2pmobile",
            "com.venmo",
            "com.squareup.cash",
            "com.zellepay.zelle",
            "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user", // Google Pay India
            "com.phonepe.app",
            "net.one97.paytm",
            "in.org.npci.upiapp", // BHIM
            "in.amazon.mShop.android.shopping", // Amazon India also handles payments
            "com.samsung.android.spay",
            "com.americanexpress.android.acctsvcs",
            // EU / UK
            "com.monzo.app",
            "com.revolut.android",
            "co.uk.starlingbank.banking",
            "uk.co.santander.santanderUK",
            "uk.co.bankofscotland.businessbank",
            "com.barclays.android.barclaysmobilebanking",
            // Crypto / trading
            "com.kraken.invest.app",
            "com.binance.app",
        )

        /**
         * Ride-hailing, food delivery, e-commerce, ticketing, hotel
         * booking, anything where a single tap can spend money or
         * commit the user to a transaction. These ARE allowed but
         * always pass through the confirmation gate.
         */
        val CRITICAL_DEFAULTS: Set<String> = setOf(
            // Ride-hailing
            "com.ubercab",
            "com.ubercab.driver",
            "com.lyft.android",
            "com.olacabs.customer",
            "com.bolt.consumer",
            // Food delivery
            "com.dd.doordash",
            "com.grubhub.android",
            "com.ubercab.eats",
            "com.application.zomato",
            "in.swiggy.android",
            "com.deliveroo.orderapp",
            "com.instacart.client",
            // E-commerce
            "com.amazon.mShop.android.shopping",
            "com.amazon.windowshop",
            "com.flipkart.android",
            "com.ebay.mobile",
            "com.target.ui",
            "com.walmart.android",
            "com.bestbuy.android",
            "com.shop.shop",
            "com.contextlogic.wish",
            "com.aliexpress.buyer",
            "com.alibaba.aliexpresshd",
            // Ticketing / travel
            "com.kayak.android",
            "com.expedia.bookings",
            "com.booking",
            "com.airbnb.android",
            "com.tripadvisor.tripadvisor",
            "com.united.mobile.android",
            "com.delta.mobile.android",
            "com.aa.android",
            "com.southwestairlines.mobile",
            "com.ticketmaster.mobile.android.na",
            "com.stubhub",
            // Subscriptions / digital purchases
            "com.android.vending", // Play Store — in-app purchases
            "com.apple.android.music",
            "com.spotify.music",
        )
    }
}
