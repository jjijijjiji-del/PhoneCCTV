package com.cury.cctv

import android.content.Context
import androidx.camera.video.Quality

/** 앱 설정 (SharedPreferences) */
object Prefs {
    private const val NAME = "cctv"

    // 파일 분할 기준: 용량 (FAT32/구형 기기 4GB 한계보다 넉넉히 작게)
    val SPLIT_MB = intArrayOf(500, 1000, 1900)
    val SPLIT_LABELS = arrayOf("500MB", "1GB", "1.9GB (추천)")

    // 빠르게 지나가는 동물용: 비트레이트를 넉넉히 줘야 움직이는 부분이 뭉개지지 않음
    val QUALITY_LABELS = arrayOf(
        "1080p 고화질 · 움직임 선명 (추천)",
        "1080p 절약 · 용량 절반",
        "720p · 용량 최소",
    )
    // 같은 체감 화질 기준: HEVC는 H.264의 약 2/3 비트레이트면 충분
    private val QUALITY_MBPS_H264 = doubleArrayOf(12.0, 6.0, 4.0)
    private val QUALITY_MBPS_HEVC = doubleArrayOf(8.0, 4.0, 2.5)

    val CODEC_LABELS = arrayOf(
        "HEVC (H.265) · 용량 절약 (추천)",
        "H.264 · 윈도우 PC 호환성 좋음",
    )

    val CAMERA_LABELS = arrayOf("후면 카메라", "전면 카메라")

    val MAX_GB = intArrayOf(10, 20, 50, 100, 0)
    val MAX_LABELS = arrayOf("10GB", "20GB", "50GB", "100GB", "제한 없음 (저장공간 부족할 때만 삭제)")

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun splitIdx(c: Context) = sp(c).getInt("split", 2)              // 1.9GB
    fun qualityIdx(c: Context) = sp(c).getInt("quality2", 0)         // 1080p 고화질
    fun cameraIdx(c: Context) = sp(c).getInt("camera", 0)            // 후면
    fun maxIdx(c: Context) = sp(c).getInt("max2", 1)                 // 20GB
    fun audio(c: Context) = sp(c).getBoolean("audio", true)
    fun fixedFps(c: Context) = sp(c).getBoolean("fixedFps", true)
    fun codecIdx(c: Context) = sp(c).getInt("codec", 0)              // HEVC
    fun setCodecIdx(c: Context, v: Int) = sp(c).edit().putInt("codec", v).apply()
    fun wantHevc(c: Context) = codecIdx(c) == 0

    fun setSplitIdx(c: Context, v: Int) = sp(c).edit().putInt("split", v).apply()
    fun setQualityIdx(c: Context, v: Int) = sp(c).edit().putInt("quality2", v).apply()
    fun setCameraIdx(c: Context, v: Int) = sp(c).edit().putInt("camera", v).apply()
    fun setMaxIdx(c: Context, v: Int) = sp(c).edit().putInt("max2", v).apply()
    fun setAudio(c: Context, v: Boolean) = sp(c).edit().putBoolean("audio", v).apply()
    fun setFixedFps(c: Context, v: Boolean) = sp(c).edit().putBoolean("fixedFps", v).apply()

    fun splitBytes(c: Context): Long = SPLIT_MB[splitIdx(c)] * 1024L * 1024L
    fun useBackCamera(c: Context) = cameraIdx(c) == 0

    /** 0이면 용량 제한 없음 */
    fun maxBytes(c: Context): Long = MAX_GB[maxIdx(c)] * 1024L * 1024L * 1024L

    fun quality(c: Context): Quality = if (qualityIdx(c) == 2) Quality.HD else Quality.FHD

    fun videoBitrate(c: Context, hevc: Boolean = wantHevc(c)): Int =
        ((if (hevc) QUALITY_MBPS_HEVC else QUALITY_MBPS_H264)[qualityIdx(c)] * 1_000_000).toInt()

    private fun totalBitsPerSec(c: Context): Double =
        videoBitrate(c) + if (audio(c)) 128_000.0 else 0.0

    /** 파일 1개가 대략 몇 분 분량인지 */
    fun minutesPerFile(c: Context): Double = splitBytes(c) / (totalBitsPerSec(c) / 8.0) / 60.0

    /** 최대 용량 기준 대략 몇 시간 분량을 보관하는지 */
    fun estimatedHours(c: Context): Double? {
        val max = maxBytes(c)
        if (max <= 0) return null
        return max / (totalBitsPerSec(c) / 8.0) / 3600.0
    }
}
