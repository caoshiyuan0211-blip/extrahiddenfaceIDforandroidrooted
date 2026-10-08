package com.jo.facelock

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * Streams `getevent -l` from a dedicated root process and counts finger-down
 * events (BTN_TOUCH DOWN). Restarts itself if the stream dies.
 */
class TouchMonitor(private val onTouchDown: () -> Unit) {
    companion object { private const val TAG = "FaceLock/Touch" }

    @Volatile private var running = false
    private var process: Process? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "touch-getevent", isDaemon = true) { loop() }
        Log.i(TAG, "TouchMonitor started")
    }

    private fun loop() {
        while (running) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "getevent -l"))
                process = p
                Log.i(TAG, "getevent process launched")
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                var line: String? = reader.readLine()
                while (running && line != null) {
                    val s = line
                    if (s.contains("BTN_TOUCH") && s.contains("DOWN")) {
                        Log.d(TAG, "touch down")
                        try { onTouchDown() } catch (_: Exception) {}
                    }
                    line = reader.readLine()
                }
                Log.w(TAG, "getevent stream ended, rc=${runCatching { p.exitValue() }.getOrNull()}")
            } catch (e: Exception) {
                Log.e(TAG, "getevent error: ${e.message}")
            }
            if (running) { try { Thread.sleep(1500) } catch (_: Exception) {} }
        }
    }

    fun stop() {
        running = false
        try { process?.destroy() } catch (_: Exception) {}
        process = null
    }
}
