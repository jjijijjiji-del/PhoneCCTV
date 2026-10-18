package com.cury.cctv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * 바로가기 / 갤럭시 모드 및 루틴 '앱 동작'에서 호출.
 * 눈에 보이는 화면 없이 녹화를 시작하거나 멈추고 닫힌다.
 */
class ShortcutActivity : Activity() {

    companion object {
        const val ACTION_START_BACK = "com.cury.cctv.SHORTCUT_START_BACK"
        const val ACTION_START_FRONT = "com.cury.cctv.SHORTCUT_START_FRONT"
        const val ACTION_STOP = "com.cury.cctv.SHORTCUT_STOP"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 잠금 화면 위에서도 동작하도록
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)

        when (intent?.action) {
            ACTION_START_BACK -> handleStart(back = true)
            ACTION_START_FRONT -> handleStart(back = false)
            ACTION_STOP -> {
                if (RecorderState.status.value.active) {
                    RecordService.stop(this)
                    Toast.makeText(this, "Claude TV 녹화를 멈춰요", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            else -> finish()
        }
    }

    private fun handleStart(back: Boolean) {
        if (RecorderState.status.value.active) {
            Toast.makeText(this, "이미 녹화 중이에요", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraOk) {
            // 권한이 없으면 앱을 열어 권한부터 받기
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        Prefs.setCameraIdx(this, if (back) 0 else 1)
        RecordService.start(this, currentRotation())
        Toast.makeText(this, if (back) "후면 카메라로 녹화를 시작해요" else "전면 카메라로 녹화를 시작해요", Toast.LENGTH_SHORT).show()
        // 안드로이드 14+는 카메라 녹화가 '앱이 화면에 있을 때' 시작돼야 해서 잠깐 머물렀다 닫음
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 2000)
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: 0
        else windowManager.defaultDisplay.rotation
}
