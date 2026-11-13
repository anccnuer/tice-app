package com.example.tice.util

import java.time.LocalDate

/**
 * 班级名称里的「2026级」是入学年份，需要换算成当前年级显示。
 *
 * 例如 2026-2027 学年：2026级 → 1年级、2025级 → 2年级……；跨学年后自动顺延。
 * 无法从班级名称解析时，回退到国标年级编号：11-16 小学、21-23 初中、31-33 高中。
 */
object GradeUtil {
    private val ENROLL_RE = Regex("(\\d{4})\\s*级")
    private val CLASS_NO_RE = Regex("(\\d+)\\s*班")

    /** 当前学年：9 月及以后算入新学年。 */
    fun schoolYear(now: LocalDate = LocalDate.now()): Int =
        if (now.monthValue >= 9) now.year else now.year - 1

    fun gradeOf(className: String, gradeId: String, now: LocalDate = LocalDate.now()): Int? {
        val enrollYear = ENROLL_RE.find(className)?.groupValues?.get(1)?.toIntOrNull()
        if (enrollYear != null) {
            val grade = schoolYear(now) - enrollYear + 1
            if (grade in 1..12) return grade
        }
        val id = gradeId.trim().toIntOrNull() ?: return null
        return when (id) {
            in 11..16 -> id - 10
            in 21..23 -> id - 14
            in 31..33 -> id - 21
            else -> null
        }
    }

    /**
     * 统一显示为「x年级x班」。
     *
     * 例如「2026级小学1班」→「1年级1班」，去掉原始名称里的学段、年份等前缀。
     * 名称里没有「x班」时只显示年级；完全无法解析年级时原样返回。
     */
    fun displayClassName(className: String, gradeId: String = ""): String {
        val grade = gradeOf(className, gradeId)
        val classNo = CLASS_NO_RE.find(className)?.groupValues?.get(1)
        return when {
            grade != null && classNo != null -> "${grade}年级${classNo}班"
            grade != null -> "${grade}年级"
            else -> className
        }
    }

    /** 年级筛选标签：「1年级」。 */
    fun gradeLabel(gradeId: String, className: String = ""): String =
        gradeOf(className, gradeId)?.let { "${it}年级" } ?: "${gradeId}年级"
}
