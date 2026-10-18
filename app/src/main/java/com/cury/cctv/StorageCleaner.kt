package com.cury.cctv

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File

/** 저장된 녹화 파일 관리: 용량 계산, 오래된 파일부터 삭제 */
object StorageCleaner {
    const val FOLDER = "CCTV"
    const val PREFIX = "CCTV_"
    private const val RESERVE_BYTES = 1L * 1024 * 1024 * 1024   // 폰에 항상 1GB는 남겨 둠

    val relativePath: String get() = "${Environment.DIRECTORY_MOVIES}/$FOLDER"

    @Suppress("DEPRECATION")
    fun legacyDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)

    @Suppress("DEPRECATION")
    fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    private class Item(val size: Long, val delete: () -> Boolean)

    /** 오래된 순서로 정렬된 녹화 파일 목록 */
    private fun listOldestFirst(c: Context): List<Item> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val out = ArrayList<Item>()
            val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
            val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("$relativePath%", "$PREFIX%")
            try {
                c.contentResolver.query(
                    base, proj, sel, args, "${MediaStore.MediaColumns.DATE_ADDED} ASC"
                )?.use { cur ->
                    val idCol = cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val sizeCol = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (cur.moveToNext()) {
                        val uri = ContentUris.withAppendedId(base, cur.getLong(idCol))
                        out += Item(cur.getLong(sizeCol)) {
                            try {
                                c.contentResolver.delete(uri, null, null) > 0
                            } catch (e: SecurityException) {
                                false // 앱 재설치 전에 만든 파일 등은 지울 권한이 없음
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // 조회 실패 시 정리 생략
            }
            out
        } else {
            val files = legacyDir().listFiles { f ->
                f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".mp4")
            } ?: emptyArray()
            files.sortedBy { it.lastModified() }.map { f -> Item(f.length()) { f.delete() } }
        }
    }

    /** 저장된 영상 개수와 총 용량 */
    fun usage(c: Context): Pair<Int, Long> {
        val items = listOldestFirst(c)
        return items.size to items.sumOf { it.size }
    }

    /**
     * 다음 파일(needBytes)을 쓸 자리를 만든다.
     * - 최대 용량(maxBytes, 0이면 무제한)을 넘으면 오래된 것부터 삭제
     * - 폰 남은 공간이 (1GB + 다음 파일 크기)보다 적으면 오래된 것부터 삭제
     * @return 삭제한 파일 수
     */
    fun makeRoom(c: Context, maxBytes: Long, needBytes: Long): Int {
        val items = ArrayDeque(listOldestFirst(c))
        var total = items.sumOf { it.size }
        var free = freeBytes()
        var deleted = 0
        while (items.isNotEmpty() &&
            ((maxBytes > 0 && total + needBytes > maxBytes) || free < RESERVE_BYTES + needBytes)
        ) {
            val item = items.removeFirst()
            if (item.delete()) {
                total -= item.size
                free += item.size
                deleted++
            }
        }
        return deleted
    }
}
