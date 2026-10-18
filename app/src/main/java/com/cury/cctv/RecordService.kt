package com.cury.cctv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 화면이 꺼져도 계속 녹화하는 포그라운드 서비스.
 * 설정한 간격마다 파일을 저장하고 곧바로 다음 파일 녹화를 시작한다.
 */
class RecordService : LifecycleService() {

    companion object {
        private const val TAG = "PhoneCCTV"
        const val ACTION_START = "com.cury.cctv.START"
        const val ACTION_STOP = "com.cury.cctv.STOP"
        const val EXTRA_ROTATION = "rotation"
        private const val CHANNEL_ID = "cctv_recording"
        private const val NOTI_ID = 1
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024
        private const val MAX_CONSECUTIVE_ERRORS = 10

        fun start(c: Context, rotation: Int) {
            val i = Intent(c, RecordService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ROTATION, rotation)
            ContextCompat.startForegroundService(c, i)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, RecordService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var running = false
    private var stopping = false
    private var audio = false
    private var splitBytes = 1900L * 1024 * 1024
    private var segments = 0
    private var consecutiveErrors = 0
    private var legacyFile: File? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (!running) {
                startSession(intent.getIntExtra(EXTRA_ROTATION, Surface.ROTATION_0))
            }
            ACTION_STOP -> requestStop("녹화를 멈췄어요")
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ───────────────────────── 세션 시작/종료 ─────────────────────────

    private fun startSession(rotation: Int) {
        createChannel()
        audio = Prefs.audio(this) && hasPerm(Manifest.permission.RECORD_AUDIO)
        splitBytes = Prefs.splitBytes(this)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (audio) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                ServiceCompat.startForeground(this, NOTI_ID, buildNotification("카메라 준비 중…"), type)
            } else {
                startForeground(NOTI_ID, buildNotification("카메라 준비 중…"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            RecorderState.update { it.copy(active = false, recording = false, message = "녹화를 시작할 수 없어요: ${e.message}") }
            stopSelf()
            return
        }

        running = true
        stopping = false
        segments = 0
        consecutiveErrors = 0
        RecorderState.update {
            RecorderState.Status(active = true, sessionStartedAt = System.currentTimeMillis(), message = "카메라 준비 중…")
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneCCTV:recording").apply {
            setReferenceCounted(false)
            acquire()
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!running) return@addListener
            try {
                val p = future.get()
                provider = p

                val qualityIdx = Prefs.qualityIdx(this)
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Prefs.quality(this),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .setTargetVideoEncodingBitRate(Prefs.videoBitrate(this))
                    .build()
                Log.i(TAG, "quality idx=$qualityIdx bitrate=${Prefs.videoBitrate(this)}")

                val selector = chooseCamera(p)
                p.unbindAll()
                // 빠른 움직임 우선: 어두워도 30fps 유지(셔터가 느려지며 생기는 잔상 줄임)
                val vc = if (Prefs.fixedFps(this)) {
                    try {
                        val fixed = VideoCapture.Builder(recorder)
                            .setTargetFrameRate(Range(30, 30))
                            .setTargetRotation(rotation)
                            .build()
                        p.bindToLifecycle(this, selector, fixed)
                        fixed
                    } catch (e: Exception) {
                        Log.w(TAG, "30fps 고정 미지원, 기본값으로", e)
                        p.unbindAll()
                        bindDefault(p, selector, recorder, rotation)
                    }
                } else {
                    bindDefault(p, selector, recorder, rotation)
                }
                videoCapture = vc
                startSegment()
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                fail("카메라를 열 수 없어요: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindDefault(
        p: ProcessCameraProvider, selector: CameraSelector, recorder: Recorder, rotation: Int
    ): VideoCapture<Recorder> {
        val vc = VideoCapture.Builder(recorder).setTargetRotation(rotation).build()
        p.bindToLifecycle(this, selector, vc)
        return vc
    }

    private fun chooseCamera(p: ProcessCameraProvider): CameraSelector {
        val wanted = if (Prefs.useBackCamera(this)) CameraSelector.DEFAULT_BACK_CAMERA
        else CameraSelector.DEFAULT_FRONT_CAMERA
        val other = if (wanted == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA
        return if (p.hasCamera(wanted)) wanted else other
    }

    /** 사용자가 멈춤을 눌렀을 때: 현재 파일을 마무리하고 종료 */
    private fun requestStop(msg: String) {
        if (!running) {
            finishSession(msg)
            return
        }
        running = false
        stopping = true
        RecorderState.update { it.copy(message = "파일 저장 중…") }
        val r = recording
        if (r == null) {
            finishSession(msg)
        } else {
            r.stop() // Finalize 이벤트에서 finishSession 호출
            main.postDelayed({ if (stopping) finishSession(msg) }, 8000) // 안전장치
        }
    }

    private fun fail(msg: String) {
        Log.w(TAG, "fail: $msg")
        requestStop(msg)
    }

    private fun finishSession(msg: String) {
        stopping = false
        running = false
        main.removeCallbacksAndMessages(null)
        recording?.close()
        recording = null
        try {
            provider?.unbindAll()
        } catch (_: Exception) {
        }
        videoCapture = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        val count = segments
        RecorderState.update {
            it.copy(
                active = false, recording = false,
                message = if (count > 0) "$msg · 이번에 저장한 파일 ${count}개" else msg
            )
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running || stopping) {
            running = false
            stopping = false
            recording?.close()
            recording = null
            try {
                provider?.unbindAll()
            } catch (_: Exception) {
            }
            wakeLock?.let { if (it.isHeld) it.release() }
            RecorderState.update { it.copy(active = false, recording = false, message = "녹화가 종료됐어요") }
        }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ───────────────────────── 파일 단위 녹화 ─────────────────────────

    @SuppressLint("MissingPermission")
    private fun startSegment() {
        if (!running) return
        val vc = videoCapture ?: return

        // 다음 파일 쓸 자리 확보 (오래된 영상 삭제)
        val need = splitBytes
        val deleted = StorageCleaner.makeRoom(this, Prefs.maxBytes(this), need)
        if (deleted > 0) Log.i(TAG, "deleted $deleted old files")
        if (StorageCleaner.freeBytes() < MIN_FREE_BYTES) {
            fail("저장공간이 부족해서 녹화를 멈췄어요")
            return
        }

        val name = StorageCleaner.PREFIX +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val pending: PendingRecording = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, StorageCleaner.relativePath)
            }
            val opts = MediaStoreOutputOptions.Builder(
                contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(cv)
                .setFileSizeLimit(splitBytes)
                .build()
            vc.output.prepareRecording(this, opts)
        } else {
            val dir = StorageCleaner.legacyDir().apply { mkdirs() }
            val f = File(dir, "$name.mp4")
            legacyFile = f
            val opts = FileOutputOptions.Builder(f)
                .setFileSizeLimit(splitBytes)
                .build()
            vc.output.prepareRecording(this, opts)
        }
        if (audio) pending.withAudioEnabled()

        try {
            recording = pending.start(ContextCompat.getMainExecutor(this)) { e -> onEvent(e, name) }
        } catch (e: Exception) {
            Log.e(TAG, "start recording failed", e)
            retryLater("녹화 시작 오류: ${e.message}")
        }
    }

    private fun onEvent(e: VideoRecordEvent, name: String) {
        when (e) {
            is VideoRecordEvent.Start -> {
                segments++
                RecorderState.update {
                    it.copy(
                        recording = true, segmentStartedAt = System.currentTimeMillis(),
                        fileName = name, segments = segments, message = ""
                    )
                }
                updateNotification()
            }

            is VideoRecordEvent.Finalize -> {
                recording = null
                RecorderState.update { it.copy(recording = false) }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    legacyFile?.let { MediaScannerConnection.scanFile(this, arrayOf(it.path), arrayOf("video/mp4"), null) }
                }

                if (stopping) {
                    finishSession("녹화를 멈췄어요")
                    return
                }
                if (!running) return

                when (e.error) {
                    VideoRecordEvent.Finalize.ERROR_NONE,
                    VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
                    VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> {
                        // 정상: 설정한 시간이 차서 파일 저장됨 → 바로 다음 파일
                        consecutiveErrors = 0
                        startSegment()
                    }
                    else -> {
                        Log.w(TAG, "finalize error=${e.error}", e.cause)
                        retryLater("녹화가 끊겨서 다시 시작하는 중… (코드 ${e.error})")
                    }
                }
            }
        }
    }

    private fun retryLater(msg: String) {
        consecutiveErrors++
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            fail("오류가 계속돼서 녹화를 멈췄어요 ($msg)")
            return
        }
        RecorderState.update { it.copy(message = msg) }
        main.postDelayed({ startSegment() }, 3000L * consecutiveErrors)
    }

    // ───────────────────────── 알림 ─────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "CCTV 녹화", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("CCTV 녹화 중")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "녹화 멈추기", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        val size = Prefs.SPLIT_LABELS[Prefs.splitIdx(this)].substringBefore(" ")
        val text = "${segments}번째 파일 녹화 중 · ${size}마다 저장"
        try {
            getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
