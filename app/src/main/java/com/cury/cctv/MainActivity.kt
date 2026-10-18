package com.cury.cctv

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var previewMsg: TextView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var storageInfo: TextView
    private lateinit var batteryInfo: TextView
    private lateinit var estimate: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnBattery: MaterialButton
    private lateinit var settingsBox: View
    private lateinit var spSegment: Spinner
    private lateinit var spQuality: Spinner
    private lateinit var spCamera: Spinner
    private lateinit var spMax: Spinner
    private lateinit var swAudio: MaterialSwitch

    private val ticker = Handler(Looper.getMainLooper())
    private var wasActive = false
    private var startAfterPermission = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val cameraOk = hasPerm(Manifest.permission.CAMERA)
            if (!cameraOk) {
                Toast.makeText(this, "카메라 권한이 있어야 녹화할 수 있어요", Toast.LENGTH_LONG).show()
                startAfterPermission = false
                return@registerForActivityResult
            }
            if (result[Manifest.permission.RECORD_AUDIO] == false) {
                Toast.makeText(this, "마이크 권한이 없어 소리 없이 녹화해요", Toast.LENGTH_SHORT).show()
            }
            if (startAfterPermission) {
                startAfterPermission = false
                startRecording()
            } else {
                bindPreview()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        previewMsg = findViewById(R.id.previewMsg)
        status = findViewById(R.id.status)
        detail = findViewById(R.id.detail)
        storageInfo = findViewById(R.id.storageInfo)
        batteryInfo = findViewById(R.id.batteryInfo)
        estimate = findViewById(R.id.estimate)
        btnStart = findViewById(R.id.btnStart)
        btnBattery = findViewById(R.id.btnBattery)
        settingsBox = findViewById(R.id.settingsBox)
        spSegment = findViewById(R.id.spSegment)
        spQuality = findViewById(R.id.spQuality)
        spCamera = findViewById(R.id.spCamera)
        spMax = findViewById(R.id.spMax)
        swAudio = findViewById(R.id.swAudio)

        setupSpinner(spSegment, Prefs.SEGMENT_LABELS, Prefs.segmentIdx(this)) { Prefs.setSegmentIdx(this, it) }
        setupSpinner(spQuality, Prefs.QUALITY_LABELS, Prefs.qualityIdx(this)) { Prefs.setQualityIdx(this, it) }
        setupSpinner(spCamera, Prefs.CAMERA_LABELS, Prefs.cameraIdx(this)) {
            val changed = it != Prefs.cameraIdx(this)
            Prefs.setCameraIdx(this, it)
            if (changed) bindPreview()
        }
        setupSpinner(spMax, Prefs.MAX_LABELS, Prefs.maxIdx(this)) { Prefs.setMaxIdx(this, it) }
        swAudio.isChecked = Prefs.audio(this)
        swAudio.setOnCheckedChangeListener { _, checked ->
            Prefs.setAudio(this, checked)
            renderEstimate()
            if (checked && !hasPerm(Manifest.permission.RECORD_AUDIO)) {
                permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        }

        btnStart.setOnClickListener {
            if (RecorderState.status.value.active) {
                RecordService.stop(this)
            } else {
                val missing = missingPermissions()
                if (missing.isEmpty()) startRecording()
                else {
                    startAfterPermission = true
                    permLauncher.launch(missing.toTypedArray())
                }
            }
        }

        btnBattery.setOnClickListener { requestIgnoreBatteryOptimization() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecorderState.status.collect { render(it) }
            }
        }
        // 저장공간·배터리 정보는 15초마다 갱신
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshStorage()
                    refreshBattery()
                    delay(15_000)
                }
            }
        }

        renderEstimate()
        val missing = missingPermissions()
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        if (!RecorderState.status.value.active && hasPerm(Manifest.permission.CAMERA)) bindPreview()
        ticker.post(tick)
    }

    override fun onResume() {
        super.onResume()
        btnBattery.visibility = if (isIgnoringBatteryOptimization()) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        ticker.removeCallbacks(tick)
        super.onStop()
    }

    // ───────────────────────── 녹화 시작 ─────────────────────────

    private fun startRecording() {
        // 미리보기가 잡고 있는 카메라를 놓아주고 서비스에 넘김
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (_: Exception) {
        }
        RecordService.start(this, currentRotation())
        if (!isIgnoringBatteryOptimization()) {
            Toast.makeText(this, "오래 녹화하려면 '배터리 최적화 해제'를 눌러 주세요", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: 0
        else windowManager.defaultDisplay.rotation

    // ───────────────────────── 미리보기 ─────────────────────────

    private fun bindPreview() {
        if (RecorderState.status.value.active || !hasPerm(Manifest.permission.CAMERA)) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (RecorderState.status.value.active) return@addListener
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@addListener
            try {
                val p = future.get()
                val pv = Preview.Builder().build()
                pv.setSurfaceProvider(preview.surfaceProvider)
                val wanted = if (Prefs.useBackCamera(this)) CameraSelector.DEFAULT_BACK_CAMERA
                else CameraSelector.DEFAULT_FRONT_CAMERA
                val other = if (wanted == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA
                p.unbindAll()
                p.bindToLifecycle(this, if (p.hasCamera(wanted)) wanted else other, pv)
                previewMsg.visibility = View.GONE
            } catch (e: Exception) {
                previewMsg.text = "미리보기를 열 수 없어요\n${e.message}"
                previewMsg.visibility = View.VISIBLE
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ───────────────────────── 화면 표시 ─────────────────────────

    private fun render(s: RecorderState.Status) {
        if (s.active) {
            btnStart.text = "녹화 멈추기"
            btnStart.setBackgroundColor(ContextCompat.getColor(this, R.color.rec))
            previewMsg.text = "녹화 중에는 미리보기가 꺼져요\n(녹화는 계속되고 있어요)"
            previewMsg.visibility = View.VISIBLE
        } else {
            btnStart.text = "녹화 시작"
            btnStart.setBackgroundColor(ContextCompat.getColor(this, R.color.accent))
            if (wasActive) {
                previewMsg.visibility = View.GONE
                bindPreview() // 녹화가 끝나면 미리보기 다시 켜기
            }
        }
        setEnabledRecursive(settingsBox, !s.active)
        wasActive = s.active
        renderStatusText()
    }

    private val tick = object : Runnable {
        override fun run() {
            renderStatusText()
            ticker.postDelayed(this, 1000)
        }
    }

    private fun renderStatusText() {
        val s = RecorderState.status.value
        val now = System.currentTimeMillis()
        if (s.active) {
            status.setTextColor(ContextCompat.getColor(this, R.color.rec))
            status.text = if (s.recording) "● 녹화 중" else "● 준비 중"
            val total = if (s.sessionStartedAt > 0) fmt(now - s.sessionStartedAt) else "-"
            val seg = if (s.recording) fmt(now - s.segmentStartedAt) else "-"
            val segLen = Prefs.SEGMENT_LABELS[Prefs.segmentIdx(this)]
            val lines = mutableListOf(
                "총 녹화 시간 $total",
                "현재 파일 ${s.segments}번째 · $seg / $segLen",
            )
            if (s.fileName.isNotEmpty()) lines += s.fileName + ".mp4"
            if (s.message.isNotEmpty()) lines += s.message
            detail.text = lines.joinToString("\n")
        } else {
            status.setTextColor(ContextCompat.getColor(this, R.color.sub))
            status.text = "● 대기 중"
            detail.text = s.message.ifEmpty { "카메라 각도를 맞춘 뒤 녹화 시작을 누르세요" }
        }
    }

    private fun renderEstimate() {
        val seg = Prefs.estimatedSegmentBytes(this) / (1024.0 * 1024.0)
        val hours = Prefs.estimatedHours(this)
        val keep = if (hours == null) "폰 저장공간이 허락하는 만큼 보관"
        else String.format(Locale.KOREA, "최근 약 %.0f시간 분량 보관", hours)
        estimate.text = String.format(Locale.KOREA, "파일 1개 약 %.0fMB · %s", seg, keep)
    }

    private suspend fun refreshStorage() {
        val (count, bytes) = withContext(Dispatchers.IO) { StorageCleaner.usage(this@MainActivity) }
        val free = withContext(Dispatchers.IO) { StorageCleaner.freeBytes() }
        storageInfo.text = String.format(
            Locale.KOREA, "저장된 영상 %d개 · %s  |  폰 남은 공간 %s", count, gb(bytes), gb(free)
        )
    }

    private fun refreshBattery() {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val warn = if (temp >= 45) "  ⚠ 폰이 뜨거워요" else ""
        batteryInfo.text = String.format(
            Locale.KOREA, "배터리 %d%% · %s · %.1f℃%s",
            pct, if (plugged) "충전 중" else "충전기 연결 안 됨", temp, warn
        )
    }

    // ───────────────────────── 권한/배터리 ─────────────────────────

    private fun missingPermissions(): List<String> {
        val need = mutableListOf(Manifest.permission.CAMERA)
        if (Prefs.audio(this)) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        return need.filter { !hasPerm(it) }
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBatteryOptimization(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ───────────────────────── 유틸 ─────────────────────────

    private fun setupSpinner(sp: Spinner, labels: Array<String>, selected: Int, onPick: (Int) -> Unit) {
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = ad
        sp.setSelection(selected, false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onPick(position)
                renderEstimate()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setEnabledRecursive(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.5f
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                c.isEnabled = enabled
            }
        }
    }

    private fun fmt(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.KOREA, "%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
    }

    private fun gb(bytes: Long): String =
        if (bytes == Long.MAX_VALUE) "?" else String.format(Locale.KOREA, "%.1fGB", bytes / (1024.0 * 1024 * 1024))
}
