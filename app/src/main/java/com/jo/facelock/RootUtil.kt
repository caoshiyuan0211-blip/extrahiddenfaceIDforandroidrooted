package com.jo.facelock

import android.util.Log
import java.io.DataOutputStream

/**
 * Thin wrapper over `su`. Used to:
 *  - read global touch events (getevent) regardless of which app is on top
 *  - force the lock screen to the front even from background (bypasses
 *    Android's background-activity-start restrictions)
 *  - collapse the notification shade so it can't be pulled down while locked
 */
object RootUtil {
    private const val TAG = "FaceLock/Root"

    /** Run several shell lines as root, fire-and-forget. Returns true if su launched. */
    fun runAsRoot(vararg cmds: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("su")
            DataOutputStream(p.outputStream).use { os ->
                for (c in cmds) {
                    os.writeBytes(c + "\n")
                }
                os.writeBytes("exit\n")
                os.flush()
            }
            p.waitFor()
            true
        } catch (e: Exception) {
            Log.e(TAG, "su failed: ${e.message}")
            false
        }
    }

    /** Bring LockActivity to the foreground via root. Works even from background. */
    fun forceLockToFront() {
        runAsRoot(
            "am start -n com.jo.facelock/.LockActivity --activity-single-top",
            "cmd statusbar collapse"
        )
    }

    fun available(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
