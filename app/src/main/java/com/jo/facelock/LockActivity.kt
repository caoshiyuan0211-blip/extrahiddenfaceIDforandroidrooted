package com.jo.facelock

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class LockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_lock)
        hideSystemBars()

        // if somehow not locked, just leave
        if (!LockState.isLocked(this)) { finish(); return }

        val pwd = findViewById<EditText>(R.id.lockPwd)
        val hint = findViewById<TextView>(R.id.lockHint)
        findViewById<Button>(R.id.btnUnlock).setOnClickListener {
            val input = pwd.text.toString()
            if (LockState.checkPassword(this, input)) {
                LockState.setLocked(this, false)
                Toast.makeText(this, "已解锁", Toast.LENGTH_SHORT).show()
                finishAffinity()
            } else {
                pwd.setText("")
                hint.text = "密码错误，请重试"
            }
        }

        // back does nothing
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* swallow */ }
        })
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // home / recents pressed: if still locked, slam back to front
        if (LockState.isLocked(this)) RootUtil.forceLockToFront()
    }

    override fun onPause() {
        super.onPause()
        if (LockState.isLocked(this)) RootUtil.forceLockToFront()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
