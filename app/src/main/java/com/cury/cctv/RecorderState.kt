package com.cury.cctv

import kotlinx.coroutines.flow.MutableStateFlow

/** 녹화 서비스와 화면이 공유하는 현재 상태 */
object RecorderState {
    data class Status(
        val active: Boolean = false,        // 서비스가 녹화 세션을 돌리는 중
        val recording: Boolean = false,     // 지금 파일에 실제로 기록 중
        val sessionStartedAt: Long = 0L,
        val segmentStartedAt: Long = 0L,
        val fileName: String = "",
        val segments: Int = 0,
        val message: String = "",
        val codec: String = "",
    )

    val status = MutableStateFlow(Status())

    fun update(block: (Status) -> Status) {
        status.value = block(status.value)
    }
}
