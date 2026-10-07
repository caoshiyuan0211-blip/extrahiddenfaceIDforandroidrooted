package com.jo.facelock

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * All persisted state lives here (SharedPreferences).
 * - app password (salted SHA-256, never stored in clear)
 * - enrolled face embedding (512 floats)
 * - locked flag (survives screen off/on and reboot)
 */
object LockState {
    private const val PREF = "facelock_state"
    private const val K_SALT = "pwd_salt"
    private const val K_HASH = "pwd_hash"
    private const val K_FACE = "face_vec"
    private const val K_LOCKED = "locked"
    private const val K_ENABLED = "enabled"

    // --- tunables (adjust on device) ---
    const val MATCH_THRESHOLD = 0.68f      // cosine similarity; higher = stricter
    const val TRIGGER_TOUCH_COUNT = 2      // touches after screen-on before a face check
    const val CHECK_WINDOW_MS = 4000L      // how long to look for a matching face
    const val LOCK_ON_NO_MATCH = true      // true = no matching face in window -> lock

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------- password ----------
    fun hasPassword(ctx: Context) = sp(ctx).contains(K_HASH)

    fun setPassword(ctx: Context, pwd: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = sha256(salt + pwd.toByteArray())
        sp(ctx).edit()
            .putString(K_SALT, toHex(salt))
            .putString(K_HASH, toHex(hash))
            .apply()
    }

    fun checkPassword(ctx: Context, pwd: String): Boolean {
        val saltHex = sp(ctx).getString(K_SALT, null) ?: return false
        val hashHex = sp(ctx).getString(K_HASH, null) ?: return false
        val calc = toHex(sha256(fromHex(saltHex) + pwd.toByteArray()))
        return calc == hashHex
    }

    // ---------- face ----------
    fun hasFace(ctx: Context) = sp(ctx).contains(K_FACE)

    fun saveFace(ctx: Context, vec: FloatArray) {
        sp(ctx).edit().putString(K_FACE, vec.joinToString(",")).apply()
    }

    fun loadFace(ctx: Context): FloatArray? {
        val s = sp(ctx).getString(K_FACE, null) ?: return null
        return try {
            s.split(",").map { it.toFloat() }.toFloatArray()
        } catch (e: Exception) {
            null
        }
    }

    // ---------- locked flag ----------
    fun isLocked(ctx: Context) = sp(ctx).getBoolean(K_LOCKED, false)
    fun setLocked(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_LOCKED, v).apply()

    // ---------- enabled flag ----------
    fun isEnabled(ctx: Context) = sp(ctx).getBoolean(K_ENABLED, false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_ENABLED, v).apply()

    // ---------- helpers ----------
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun fromHex(s: String) =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) + s[it * 2 + 1].digitToInt(16)).toByte() }
}
