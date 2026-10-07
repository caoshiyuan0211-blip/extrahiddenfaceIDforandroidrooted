package com.jo.facelock

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * Counts global finger-down events by streaming `getevent -l` as root.
 * A tap or the start of a swipe = one BTN_TOUCH DOWN (fallback: a new
 * ABS_MT_TRACKING_ID whose value != ffffffff). Works over any app / any
 * screen because root reads the raw input devices directly.
 */
class TouchMonitor(private val onTouchDown: () -> Unit) {
    companion object { private const val TAG = "FaceLock/Touch" }

    @Volatile private var running = false
    private var process: Process? = null
    private var sawBtnTouch = false

    fun start() {
        if (running) return
        running = true
        thread(name = "touch-getevent") {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "getevent -l"))
                process = p
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                
                while (running) {
                    val line = reader.readLine() ?: break
                    val s = line ?: continue
                    when {
                        s.contains("BTN_TOUCH") && s.contains("DOWN") -> {
                            sawBtnTouch = true
                            fire()
                        }
                        // fallback only for devices that never emit BTN_TOUCH
                        !sawBtnTouch && s.contains("ABS_MT_TRACKING_ID") &&
                            !s.trimEnd().endsWith("ffffffff") -> fire()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getevent stream error: ${e.message}")
            }
        }
    }

    private fun fire() {
        try { onTouchDown() } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        try { process?.destroy() } catch (_: Exception) {}
        process = null
    }
}
