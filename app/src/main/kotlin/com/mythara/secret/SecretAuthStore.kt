package com.mythara.secret

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secret-mode password store. The Observe controls (M8+) sit behind a
 * password completely separate from the device unlock. This store holds:
 *  - a random 16-byte salt
 *  - PBKDF2-HMAC-SHA256 derived hash (100k iterations, 32 bytes)
 *  - a fail-attempt counter + cooldown timestamp
 *
 * Plain stdlib crypto (no Argon2 dependency) — 100k PBKDF2 iterations is
 * fine for a personal-device password gate. If we ever need parameter
 * agility we can rotate iterations behind a `version` field.
 *
 * What this is NOT:
 *  - A second-factor of the MiniMax key or GitHub PAT — those live in
 *    their own Tink-AEAD stores.
 *  - A device-credential proxy — that's already AuthGate on the outside.
 *
 * The lock is layered: device credential → app → secret password → Observe.
 */
@Singleton
class SecretAuthStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "mythara_secret_auth")

    private val keySalt           = stringPreferencesKey("salt.b64")
    private val keyHash           = stringPreferencesKey("hash.b64")
    private val keyFailedAttempts = intPreferencesKey("attempts.failed")
    private val keyCooldownUntil  = longPreferencesKey("cooldown.untilMs")

    suspend fun hasPassword(): Boolean {
        val p = ctx.store.data.first()
        return !p[keyHash].isNullOrBlank() && !p[keySalt].isNullOrBlank()
    }

    suspend fun setPassword(plain: String) {
        require(plain.length >= MIN_LENGTH) { "password must be at least $MIN_LENGTH characters" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(plain, salt)
        ctx.store.edit {
            it[keySalt] = Base64.encodeToString(salt, Base64.NO_WRAP)
            it[keyHash] = Base64.encodeToString(hash, Base64.NO_WRAP)
            it[keyFailedAttempts] = 0
            it[keyCooldownUntil] = 0L
        }
    }

    /**
     * Verify a password attempt. Returns:
     *  - VerifyResult.Ok           on success (resets attempt counter)
     *  - VerifyResult.Cooldown(ms) if too many failures recently
     *  - VerifyResult.Wrong        if hash doesn't match
     *  - VerifyResult.Unset        if no password is set
     */
    suspend fun verify(plain: String): VerifyResult {
        val p = ctx.store.data.first()
        val storedSaltB64 = p[keySalt] ?: return VerifyResult.Unset
        val storedHashB64 = p[keyHash] ?: return VerifyResult.Unset
        val cooldownUntil = p[keyCooldownUntil] ?: 0L
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) return VerifyResult.Cooldown(cooldownUntil - now)

        val salt = Base64.decode(storedSaltB64, Base64.NO_WRAP)
        val expectHash = Base64.decode(storedHashB64, Base64.NO_WRAP)
        val gotHash = derive(plain, salt)
        return if (MessageDigest.isEqual(expectHash, gotHash)) {
            ctx.store.edit { it[keyFailedAttempts] = 0; it[keyCooldownUntil] = 0L }
            VerifyResult.Ok
        } else {
            val failed = (p[keyFailedAttempts] ?: 0) + 1
            val cooldown = if (failed >= MAX_ATTEMPTS_BEFORE_COOLDOWN) {
                now + COOLDOWN_DURATION_MS
            } else cooldownUntil
            ctx.store.edit {
                it[keyFailedAttempts] = if (failed >= MAX_ATTEMPTS_BEFORE_COOLDOWN) 0 else failed
                it[keyCooldownUntil] = cooldown
            }
            VerifyResult.Wrong
        }
    }

    suspend fun clear() {
        ctx.store.edit {
            it.remove(keySalt); it.remove(keyHash)
            it.remove(keyFailedAttempts); it.remove(keyCooldownUntil)
        }
    }

    private fun derive(plain: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(plain.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return skf.generateSecret(spec).encoded
    }

    sealed interface VerifyResult {
        data object Ok : VerifyResult
        data object Wrong : VerifyResult
        data object Unset : VerifyResult
        data class Cooldown(val millisRemaining: Long) : VerifyResult
    }

    companion object {
        const val MIN_LENGTH = 6
        private const val SALT_BYTES = 16
        private const val ITERATIONS = 100_000
        private const val KEY_BITS = 256
        const val MAX_ATTEMPTS_BEFORE_COOLDOWN = 5
        const val COOLDOWN_DURATION_MS = 5L * 60_000L // 5 minutes
    }
}
