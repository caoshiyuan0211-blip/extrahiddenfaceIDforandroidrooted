package com.jo.facelock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var previewView: PreviewView
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var recognizer: FaceRecognizer? = null
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile private var enrolling = false
    @Volatile private var busy = false
    private val enrollVectors = mutableListOf<FloatArray>()
    private val NEED = 10

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        previewView = findViewById(R.id.preview)

        requestBasePermissions()

        findViewById<Button>(R.id.btnSavePwd).setOnClickListener { savePassword() }
        findViewById<Button>(R.id.btnEnroll).setOnClickListener { startEnroll() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startProtection() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopProtection() }
        findViewById<Button>(R.id.btnTestLock).setOnClickListener { testLock() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------------- permissions ----------------
    private fun requestBasePermissions() {
        val need = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(need.toTypedArray())
    }

    // ---------------- password ----------------
    private fun savePassword() {
        val p1 = findViewById<EditText>(R.id.pwd1).text.toString()
        val p2 = findViewById<EditText>(R.id.pwd2).text.toString()
        if (p1.isEmpty() || p1 != p2) {
            toast("两次密码不一致或为空")
            return
        }
        LockState.setPassword(this, p1)
        toast("密码已保存")
        refreshStatus()
    }

    // ---------------- face enrollment ----------------
    private fun startEnroll() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("需要相机权限"); requestBasePermissions(); return
        }
        enrollVectors.clear()
        enrolling = true
        previewView.visibility = android.view.View.VISIBLE
        statusText.text = "正在初始化模型…"

        thread {
            if (recognizer == null) {
                try { recognizer = FaceRecognizer(this) }
                catch (e: Exception) { runOnUiThread { toast("模型加载失败: ${e.message}") }; return@thread }
            }
            runOnUiThread { bindEnrollCamera() }
        }
    }

    private fun bindEnrollCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> enrollFrame(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                statusText.text = "请正对屏幕，采集中 0/$NEED"
            } catch (e: Exception) {
                toast("相机启动失败: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun enrollFrame(proxy: ImageProxy) {
        if (!enrolling || busy) { proxy.close(); return }
        busy = true
        try {
            val deg = proxy.imageInfo.rotationDegrees
            val bmp = proxy.toBitmap().rotate(deg)
            proxy.close()
            val vec = recognizer?.process(bmp)
            if (vec != null) {
                enrollVectors.add(vec)
                val c = enrollVectors.size
                runOnUiThread { statusText.text = "请正对屏幕，采集中 $c/$NEED" }
                if (c >= NEED) finishEnroll()
            }
        } catch (e: Exception) {
            try { proxy.close() } catch (_: Exception) {}
        } finally {
            busy = false
        }
    }

    private fun finishEnroll() {
        enrolling = false
        val dim = enrollVectors[0].size
        val avg = FloatArray(dim)
        for (v in enrollVectors) for (i in 0 until dim) avg[i] += v[i]
        var norm = 0f
        for (x in avg) norm += x * x
        norm = sqrt(norm)
        if (norm > 0f) for (i in avg.indices) avg[i] /= norm
        LockState.saveFace(this, avg)
        runOnUiThread {
            try { cameraProvider?.unbindAll() } catch (_: Exception) {}
            previewView.visibility = android.view.View.GONE
            toast("人脸录入完成")
            refreshStatus()
        }
    }

    // ---------------- start / stop ----------------
    private fun startProtection() {
        if (!LockState.hasPassword(this)) { toast("请先设置密码"); return }
        if (!LockState.hasFace(this)) { toast("请先录入人脸"); return }

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast("请授予「显示在其他应用上层」权限")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }

        LockState.setEnabled(this, true)
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        toast("保护已启动")
        refreshStatus()
    }

    private fun stopProtection() {
        LockState.setEnabled(this, false)
        LockState.setLocked(this, false)
        stopService(Intent(this, MonitorService::class.java))
        toast("保护已停止")
        refreshStatus()
    }

    private fun testLock() {
        if (!LockState.hasPassword(this)) { toast("请先设置密码"); return }
        LockState.setLocked(this, true)
        LockState.setEnabled(this, true)
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        startActivity(Intent(this, LockActivity::class.java))
    }

    // ---------------- status ----------------
    private fun refreshStatus() {
        thread {
            val root = RootUtil.available()
            val sb = StringBuilder()
            sb.append("Root: ").append(if (root) "可用 ✅" else "不可用 ❌（本 app 必须 root）").append("\n")
            sb.append("密码: ").append(if (LockState.hasPassword(this)) "已设置 ✅" else "未设置").append("\n")
            sb.append("人脸: ").append(if (LockState.hasFace(this)) "已录入 ✅" else "未录入").append("\n")
            sb.append("保护: ").append(if (LockState.isEnabled(this)) "运行中 ✅" else "未启动")
            runOnUiThread { if (!enrolling) statusText.text = sb.toString() }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.close()
        analysisExecutor.shutdownNow()
    }

    private fun Bitmap.rotate(deg: Int): Bitmap {
        if (deg == 0) return this
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
    }
}
