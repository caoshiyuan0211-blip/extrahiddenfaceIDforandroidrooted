package com.jo.facelock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MonitorService : LifecycleService() {

    companion object {
        private const val TAG = "FaceLock/Svc"
        private const val CH_ID = "facelock_fg"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var recognizer: FaceRecognizer? = null
    private var enrolled: FloatArray? = null

    @Volatile private var armed = false
    @Volatile private var checking = false
    @Volatile private var busy = false
    @Volatile private var touchCount = 0
    @Volatile private var trusted = false
    @Volatile private var screenOffAt = 0L
    private val checkMatched = AtomicBoolean(false)
    private var checkTimeout: Job? = null
    private var watchdog: Job? = null

    private var touchMonitor: TouchMonitor? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        grantRootPerms()
        startFg()

        scope.launch {
            try { recognizer = FaceRecognizer(this@MonitorService) }
            catch (e: Exception) { Log.e(TAG, "recognizer init: ${e.message}") }
        }

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)

        touchMonitor = TouchMonitor { onTouch() }.also { it.start() }

        if (LockState.isLocked(this)) enforceLock() else arm()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (LockState.isLocked(this)) enforceLock()
        return START_STICKY
    }

    private fun onScreenOn() {
        if (LockState.isLocked(this)) { RootUtil.forceLockToFront(); return }
        trusted = false
        arm()
    }

    private fun onScreenOff() {
        screenOffAt = System.currentTimeMillis()
        disarm()
    }

    private fun arm() { armed = true; touchCount = 0 }
    private fun disarm() { armed = false }

    private fun onTouch() {
        if (LockState.isLocked(this)) return
        if (!armed || checking) return
        touchCount++
        Log.d(TAG, "touchCount=$touchCount armed=$armed trusted=$trusted")
        if (touchCount >= LockState.TRIGGER_TOUCH_COUNT) {
            disarm()
            Log.i(TAG, "trigger face check")
            startFaceCheck()
        }
    }

    private fun startFaceCheck() {
        if (checking) return
        val e = LockState.loadFace(this) ?: return
        enrolled = e
        if (recognizer == null) {
            try { recognizer = FaceRecognizer(this) } catch (ex: Exception) { return }
        }
        checking = true
        checkMatched.set(false)
        main.post { bindCamera() }
        checkTimeout = scope.launch {
            delay(LockState.CHECK_WINDOW_MS)
            endCheck()
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> handleFrame(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "camera bind: ${e.message}")
                endCheck()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleFrame(proxy: ImageProxy) {
        if (!checking || busy) { proxy.close(); return }
        busy = true
        try {
            val deg = proxy.imageInfo.rotationDegrees
            val bmp = proxy.toBitmap().rotate(deg)
            proxy.close()
            val vec = recognizer?.process(bmp)
            val ref = enrolled
            if (vec != null && ref != null) {
                val sim = cosine(vec, ref)
                Log.d(TAG, "sim=$sim")
                if (sim >= LockState.MATCH_THRESHOLD) {
                    checkMatched.set(true)
                    endCheck()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "frame: ${e.message}")
            try { proxy.close() } catch (_: Exception) {}
        } finally {
            busy = false
        }
    }

    @Synchronized
    private fun endCheck() {
        if (!checking) return
        checking = false
        checkTimeout?.cancel()
        main.post {
            try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        }
        if (checkMatched.get()) {
            trusted = true
        } else if (LockState.LOCK_ON_NO_MATCH) {
            enforceLock()
        }
    }

    private fun enforceLock() {
        LockState.setLocked(this, true)
        trusted = false
        
        RootUtil.forceLockToFront()
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (LockState.isLocked(this@MonitorService)) {
                if (!LockState.lockVisible) RootUtil.forceLockToFront()
                delay(700)
            }
            RootUtil.unpinLock()
            trusted = true
        }
    }

    private fun startFg() {
        val n: Notification = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("系统服务运行中")
            .setContentText("")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val cam = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        val types = if (Build.VERSION.SDK_INT >= 34) {
            val su = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            intArrayOf(cam or su, su, cam)
        } else {
            intArrayOf(cam)
        }
        for (t in types) {
            try { startForeground(NOTIF_ID, n, t); return }
            catch (e: Exception) { Log.e(TAG, "startFg type=$t: ${e.message}") }
        }
        try { startForeground(NOTIF_ID, n) } catch (_: Exception) {}
    }

    private fun grantRootPerms() {
        scope.launch {
            RootUtil.runAsRoot(
                "cmd appops set com.jo.facelock CAMERA allow",
                "cmd appops set com.jo.facelock SYSTEM_ALERT_WINDOW allow",
                "dumpsys deviceidle whitelist +com.jo.facelock",
                "cmd appops set com.jo.facelock START_FOREGROUND allow"
            )
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CH_ID, "系统服务", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        touchMonitor?.stop()
        recognizer?.close()
        analysisExecutor.shutdownNow()
    }

    private fun Bitmap.rotate(deg: Int): Bitmap {
        if (deg == 0) return this
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
    }
}
