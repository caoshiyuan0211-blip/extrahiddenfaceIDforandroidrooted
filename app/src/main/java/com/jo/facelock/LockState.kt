package com.jo.facelock

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

object LockState {
    private const val PREF = "facelock_state"
    private const val K_SALT = "pwd_salt"
    private const val K_HASH = "pwd_hash"
    private const val K_FACE = "face_vec"
    private const val K_LOCKED = "locked"
    private const val K_ENABLED = "enabled"

    const val MATCH_THRESHOLD = 0.68f
    const val TRIGGER_TOUCH_COUNT = 2
    const val CHECK_WINDOW_MS = 4000L
    const val LOCK_ON_NO_MATCH = true
    const val REARM_AFTER_OFF_MS = 120_000L

    @Volatile var lockVisible = false

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun hasPassword(ctx: Context) = sp(ctx).contains(K_HASH)
    fun setPassword(ctx: Context, pwd: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = sha256(salt + pwd.toByteArray())
        sp(ctx).edit().putString(K_SALT, toHex(salt)).putString(K_HASH, toHex(hash)).apply()
    }
    fun checkPassword(ctx: Context, pwd: String): Boolean {
        val saltHex = sp(ctx).getString(K_SALT, null) ?: return false
        val hashHex = sp(ctx).getString(K_HASH, null) ?: return false
        return toHex(sha256(fromHex(saltHex) + pwd.toByteArray())) == hashHex
    }
    fun hasFace(ctx: Context) = sp(ctx).contains(K_FACE)
    fun saveFace(ctx: Context, vec: FloatArray) { sp(ctx).edit().putString(K_FACE, vec.joinToString(",")).apply() }
    fun loadFace(ctx: Context): FloatArray? {
        val s = sp(ctx).getString(K_FACE, null) ?: return null
        return try { s.split(",").map { it.toFloat() }.toFloatArray() } catch (e: Exception) { null }
    }
    fun isLocked(ctx: Context) = sp(ctx).getBoolean(K_LOCKED, false)
    fun setLocked(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_LOCKED, v).apply()
    fun isEnabled(ctx: Context) = sp(ctx).getBoolean(K_ENABLED, false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_ENABLED, v).apply()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun fromHex(s: String) = ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) + s[it * 2 + 1].digitToInt(16)).toByte() }
}
