package com.jo.facelock

import android.util.Log
import java.io.DataOutputStream
import kotlin.concurrent.thread

object RootUtil {
    private const val TAG = "FaceLock/Root"
    private var shell: Process? = null
    private var os: DataOutputStream? = null

    @Synchronized
    private fun ensure(): DataOutputStream? {
        val cur = os
        if (cur != null && shell?.isAlive == true) return cur
        return try {
            val p = Runtime.getRuntime().exec("su")
            shell = p
            thread(isDaemon = true) { try { p.inputStream.bufferedReader().forEachLine { } } catch (_: Exception) {} }
            thread(isDaemon = true) { try { p.errorStream.bufferedReader().forEachLine { } } catch (_: Exception) {} }
            DataOutputStream(p.outputStream).also { os = it }
        } catch (e: Exception) {
            Log.e(TAG, "su start failed: ${e.message}"); shell = null; os = null; null
        }
    }

    @Synchronized
    fun exec(vararg cmds: String): Boolean {
        var o = ensure() ?: return false
        try {
            for (c in cmds) o.writeBytes(c + "\n")
            o.flush(); return true
        } catch (e: Exception) {
            try { shell?.destroy() } catch (_: Exception) {}
            shell = null; os = null
            o = ensure() ?: return false
            return try { for (c in cmds) o.writeBytes(c + "\n"); o.flush(); true } catch (_: Exception) { false }
        }
    }

    fun runAsRoot(vararg cmds: String): Boolean = exec(*cmds)

    fun forceLockToFront() {
        exec(
            "am start -n com.jo.facelock/.LockActivity --activity-single-top >/dev/null 2>&1",
            "cmd statusbar collapse >/dev/null 2>&1"
        )
    }

    fun available(): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0
    } catch (e: Exception) { false }

    /** Disable escape routes while locked (recents/splitscreen/gestures/statusbar). */
    fun lockdownOn() {
        exec(
            "settings put global policy_control immersive.full=com.jo.facelock >/dev/null 2>&1",
            "cmd statusbar collapse >/dev/null 2>&1",
            "wm dismiss-keyguard >/dev/null 2>&1"
        )
    }

    fun lockdownOff() {
        exec(
            "settings put global policy_control null* >/dev/null 2>&1"
        )
    }
}
