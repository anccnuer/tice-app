package com.example.tice.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间与文件名的统一入口，避免各调用点各拼一份格式。
 * 每次新建 [SimpleDateFormat]：该类型不是线程安全的，导出与 HTTP 服务可能在不同线程调用。
 */
fun exportStamp(now: Date = Date()): String =
    SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(now)

fun exportFileName(now: Date = Date()): String = "体测成绩_${exportStamp(now)}.xlsx"

fun backupFileName(now: Date = Date()): String =
    "tice-backup-" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(now) + ".zip"

fun displayDateTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

fun displayDateTimeSeconds(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(millis))
