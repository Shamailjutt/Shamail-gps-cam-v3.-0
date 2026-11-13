package com.shamail.gpscam.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class UserRole {
    ADMIN,
    GUEST
}

/**
 * Centralized role and credential manager using PBKDF2-HMAC-SHA256 salted password hashing
 * and EncryptedSharedPreferences session storage.
 * Note: Client-side/local-only authentication provides basic device access control rather than
 * tamper-proof server-grade security. Never logs, displays, or stores plaintext credentials.
 */
object AuthManager {

    private const val PREF_FILE_NAME = "shamail_secure_auth_prefs"
    private const val FALLBACK_PREF_NAME = "shamail_secure_auth_fallback"
    private const val KEY_REMEMBER_USER = "remember_user_enabled"
    private const val KEY_SESSION_TOKEN = "verified_session_token"
    private const val KEY_SAVED_ROLE = "remembered_user_role"

    // PBKDF2-HMAC-SHA256 parameters (no plaintext credentials stored)
    private const val SALT_HEX = "8f4b2e91c73a60d54e18b9f27a3c5d61"
    private const val ITERATIONS = 65536
    private const val KEY_LENGTH_BITS = 256
    private const val EXPECTED_USER_HASH = "4b27b96d0a6b5154840c926b0e3e379fe540f409f3197350e392768d17bd4d4b"
    private const val EXPECTED_PASS_HASH = "1886ff96764794d2eb51c25fd2bfade1788f54994f69a8646185c28faa45b807"

    @Volatile
    private var inMemoryRole: UserRole? = null

    private fun openSecurePrefs(context: Context): SharedPreferences {
        val appCtx = context.applicationContext
        return runCatching {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREF_FILE_NAME,
                masterKeyAlias,
                appCtx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            appCtx.getSharedPreferences(FALLBACK_PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    fun authenticate(
        context: Context,
        usernameInput: String,
        passwordInput: String,
        rememberUser: Boolean
    ): Boolean {
        val trimmedUser = usernameInput.trim()
        if (trimmedUser.isEmpty() || passwordInput.isEmpty()) return false

        val userBytes = derivePbkdf2(trimmedUser)
        val passBytes = derivePbkdf2(passwordInput)
        val expectedUserBytes = hexToBytes(EXPECTED_USER_HASH)
        val expectedPassBytes = hexToBytes(EXPECTED_PASS_HASH)

        val userMatch = MessageDigest.isEqual(userBytes, expectedUserBytes)
        val passMatch = MessageDigest.isEqual(passBytes, expectedPassBytes)
        val verified = userMatch && passMatch

        if (verified) {
            inMemoryRole = UserRole.ADMIN
            val prefs = openSecurePrefs(context)
            if (rememberUser) {
                prefs.edit()
                    .putBoolean(KEY_REMEMBER_USER, true)
                    .putString(KEY_SESSION_TOKEN, UUID.randomUUID().toString())
                    .putString(KEY_SAVED_ROLE, UserRole.ADMIN.name)
                    .apply()
            } else {
                prefs.edit()
                    .putBoolean(KEY_REMEMBER_USER, false)
                    .remove(KEY_SESSION_TOKEN)
                    .remove(KEY_SAVED_ROLE)
                    .apply()
            }
        }
        return verified
    }

    fun enterGuestMode(context: Context) {
        inMemoryRole = UserRole.GUEST
        // Explicitly ensure Guest is never persisted as Remember Me / Admin
        openSecurePrefs(context).edit()
            .putBoolean(KEY_REMEMBER_USER, false)
            .remove(KEY_SESSION_TOKEN)
            .remove(KEY_SAVED_ROLE)
            .apply()
    }

    fun isRememberedSessionActive(context: Context): Boolean {
        val prefs = openSecurePrefs(context)
        val remembered = prefs.getBoolean(KEY_REMEMBER_USER, false)
        val token = prefs.getString(KEY_SESSION_TOKEN, null)
        val savedRole = prefs.getString(KEY_SAVED_ROLE, null)
        // Backward compatibility: if an existing remembered token lacks KEY_SAVED_ROLE, do not promote Guest;
        // only allow if no explicit GUEST role was stored and token is present.
        val roleAllowed = savedRole == null || savedRole == UserRole.ADMIN.name
        return remembered && !token.isNullOrBlank() && roleAllowed
    }

    fun getCurrentRole(context: Context): UserRole {
        val localRole = inMemoryRole
        if (localRole != null) {
            return localRole
        }
        if (isRememberedSessionActive(context)) {
            inMemoryRole = UserRole.ADMIN
            return UserRole.ADMIN
        }
        return UserRole.GUEST
    }

    fun isAdmin(context: Context): Boolean {
        return getCurrentRole(context) == UserRole.ADMIN
    }

    fun hasActiveSession(context: Context): Boolean {
        if (inMemoryRole != null) return true
        if (isRememberedSessionActive(context)) {
            inMemoryRole = UserRole.ADMIN
            return true
        }
        return false
    }

    fun canEnterSettings(context: Context): Boolean {
        return isAdmin(context)
    }

    fun setRememberUserPreference(context: Context, rememberUser: Boolean) {
        // Never allow Guest to save a remembered session
        if (!isAdmin(context)) {
            openSecurePrefs(context).edit()
                .putBoolean(KEY_REMEMBER_USER, false)
                .remove(KEY_SESSION_TOKEN)
                .remove(KEY_SAVED_ROLE)
                .apply()
            return
        }
        val prefs = openSecurePrefs(context)
        if (rememberUser) {
            val existingToken = prefs.getString(KEY_SESSION_TOKEN, null)
            prefs.edit()
                .putBoolean(KEY_REMEMBER_USER, true)
                .putString(KEY_SESSION_TOKEN, existingToken ?: UUID.randomUUID().toString())
                .putString(KEY_SAVED_ROLE, UserRole.ADMIN.name)
                .apply()
        } else {
            prefs.edit()
                .putBoolean(KEY_REMEMBER_USER, false)
                .remove(KEY_SESSION_TOKEN)
                .remove(KEY_SAVED_ROLE)
                .apply()
        }
    }

    fun logout(context: Context) {
        inMemoryRole = null
        openSecurePrefs(context).edit()
            .putBoolean(KEY_REMEMBER_USER, false)
            .remove(KEY_SESSION_TOKEN)
            .remove(KEY_SAVED_ROLE)
            .apply()
    }

    private fun derivePbkdf2(input: String): ByteArray {
        val salt = hexToBytes(SALT_HEX)
        val spec = PBEKeySpec(input.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val out = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            out[i / 2] = ((hi shl 4) + lo).toByte()
            i += 2
        }
        return out
    }
}
