package com.cury.cctv

import android.content.Context
import androidx.camera.video.Quality

/** 앱 설정 (SharedPreferences) */
object Prefs {
    private const val NAME = "cctv"

    // 선택지 (화면 표시용 라벨과 실제 값)
    val SEGMENT_MIN = intArrayOf(5, 10, 15, 30, 60)
    val SEGMENT_LABELS = arrayOf("5분", "10분", "15분", "30분", "1시간")

    val QUALITY_LABELS = arrayOf("SD (480p) · 가장 적은 용량", "HD (720p) · 추천", "FHD (1080p) · 선명, 용량 큼")
    // CCTV용으로 비트레이트를 낮게 잡아 저장 용량을 아낌 (Mbps)
    val QUALITY_MBPS = doubleArrayOf(1.5, 3.0, 6.0)

    val CAMERA_LABELS = arrayOf("후면 카메라", "전면 카메라")

    val MAX_GB = intArrayOf(5, 10, 20, 50, 0)
    val MAX_LABELS = arrayOf("5GB", "10GB", "20GB", "50GB", "제한 없음 (저장공간 부족할 때만 삭제)")

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun segmentIdx(c: Context) = sp(c).getInt("segment", 3)          // 30분
    fun qualityIdx(c: Context) = sp(c).getInt("quality", 1)          // HD
    fun cameraIdx(c: Context) = sp(c).getInt("camera", 0)            // 후면
    fun maxIdx(c: Context) = sp(c).getInt("max", 1)                  // 10GB
    fun audio(c: Context) = sp(c).getBoolean("audio", true)

    fun setSegmentIdx(c: Context, v: Int) = sp(c).edit().putInt("segment", v).apply()
    fun setQualityIdx(c: Context, v: Int) = sp(c).edit().putInt("quality", v).apply()
    fun setCameraIdx(c: Context, v: Int) = sp(c).edit().putInt("camera", v).apply()
    fun setMaxIdx(c: Context, v: Int) = sp(c).edit().putInt("max", v).apply()
    fun setAudio(c: Context, v: Boolean) = sp(c).edit().putBoolean("audio", v).apply()

    fun segmentMillis(c: Context): Long = SEGMENT_MIN[segmentIdx(c)] * 60_000L
    fun useBackCamera(c: Context) = cameraIdx(c) == 0

    /** 0이면 용량 제한 없음 */
    fun maxBytes(c: Context): Long = MAX_GB[maxIdx(c)] * 1024L * 1024L * 1024L

    fun quality(c: Context): Quality = when (qualityIdx(c)) {
        0 -> Quality.SD
        2 -> Quality.FHD
        else -> Quality.HD
    }

    fun videoBitrate(c: Context): Int = (QUALITY_MBPS[qualityIdx(c)] * 1_000_000).toInt()

    /** 한 파일(분할 1개)의 예상 크기 */
    fun estimatedSegmentBytes(c: Context): Long {
        val bps = videoBitrate(c) + if (audio(c)) 128_000 else 0
        return (bps / 8.0 * (segmentMillis(c) / 1000.0)).toLong()
    }

    /** 최대 용량 기준 대략 몇 시간 분량을 보관하는지 */
    fun estimatedHours(c: Context): Double? {
        val max = maxBytes(c)
        if (max <= 0) return null
        val bps = videoBitrate(c) + if (audio(c)) 128_000 else 0
        return max / (bps / 8.0) / 3600.0
    }
}
