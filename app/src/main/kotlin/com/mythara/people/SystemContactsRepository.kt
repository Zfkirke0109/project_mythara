package com.mythara.people

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One system address-book contact, merged with any messaging
 * affordances detected via [ContactsContract.Data].
 *
 * Phase 4 of v7 reads the actual phone address book through
 * `READ_CONTACTS` (already declared + granted in this build) so the
 * new People screen reads like a real contacts app rather than the
 * Mythara-derived-only list.
 */
data class SystemContact(
    /** Stable lookup key — survives sync events that change row ids. */
    val lookupKey: String,
    /** Display name as the user entered it in the address book. */
    val displayName: String,
    /** Phone number formatted for tel: / sms: / whatsapp:// intents.
     *  When possible we keep the address-book formatting; the calling
     *  intents accept any reasonable shape. Null when the contact has
     *  no phone number on file. */
    val primaryPhone: String?,
    /** Address-book photo URI if any (content://). */
    val photoUri: String?,
    /** True when this contact has a WhatsApp data row — i.e. the user
     *  can be reached via WhatsApp (chat / call) directly. */
    val hasWhatsApp: Boolean,
)

/**
 * Reads system contacts via [ContactsContract] for the new contacts-
 * style People screen. Singleton — caches on first load, refreshable
 * on demand (the People VM kicks a reload on Activity resume).
 *
 * Reading is cheap on modern Pixels (~80 ms for ~600 contacts on the
 * test cluster). Runs on Dispatchers.IO so it never blocks the UI.
 */
@Singleton
class SystemContactsRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    /** True when READ_CONTACTS is currently granted. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Load every visible contact with a phone number. Each contact is
     * deduped by [lookupKey] (so contacts with multiple numbers
     * surface once, with one primary number). Returns an empty list
     * when permission isn't granted — caller surfaces a request.
     */
    suspend fun loadAll(): List<SystemContact> = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            Log.d(TAG, "loadAll: READ_CONTACTS not granted")
            return@withContext emptyList()
        }

        // Step 1: collect lookupKey → (display name, number, photo).
        // We query Phone.CONTENT_URI because it gives us the number
        // directly. One row per phone — we dedupe by lookupKey.
        val byKey = LinkedHashMap<String, SystemContact>(256)
        val phoneCols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
        )
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                phoneCols,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { c ->
                val iLookup = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
                val iName = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val iNumber = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val iPhoto = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                while (c.moveToNext()) {
                    val key = c.getString(iLookup) ?: continue
                    val name = c.getString(iName)?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    if (key in byKey) continue
                    byKey[key] = SystemContact(
                        lookupKey = key,
                        displayName = name,
                        primaryPhone = c.getString(iNumber)?.trim()?.takeIf { it.isNotBlank() },
                        photoUri = c.getString(iPhoto),
                        hasWhatsApp = false,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "phone-cursor query failed: ${it.message}") }

        // Step 2: WhatsApp data rows — flag every contact that has one.
        // WhatsApp registers two custom mimetypes: profile + voip.call.
        // Either one's presence means the contact is reachable.
        val whatsAppKeys = HashSet<String>()
        val dataCols = arrayOf(ContactsContract.Data.LOOKUP_KEY)
        val mimetypes = arrayOf(WA_PROFILE_MIME, WA_VOIP_MIME)
        for (mt in mimetypes) {
            runCatching {
                ctx.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    dataCols,
                    "${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(mt),
                    null,
                )?.use { c ->
                    val iLk = c.getColumnIndexOrThrow(ContactsContract.Data.LOOKUP_KEY)
                    while (c.moveToNext()) {
                        c.getString(iLk)?.let { whatsAppKeys += it }
                    }
                }
            }.onFailure { Log.d(TAG, "whatsapp data-row query failed for $mt: ${it.message}") }
        }

        // Merge WhatsApp flag into the contact list.
        val out = ArrayList<SystemContact>(byKey.size)
        for ((key, sc) in byKey) {
            out += if (key in whatsAppKeys) sc.copy(hasWhatsApp = true) else sc
        }
        Log.d(TAG, "loadAll: ${out.size} contacts (${whatsAppKeys.size} have WhatsApp)")
        out
    }

    companion object {
        private const val TAG = "Mythara/SysContacts"

        /** WhatsApp custom mimetypes — profile (chat) + voip.call. */
        private const val WA_PROFILE_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
        private const val WA_VOIP_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
    }
}
