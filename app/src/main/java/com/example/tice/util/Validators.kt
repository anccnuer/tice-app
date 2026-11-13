package com.example.tice.util

import com.example.tice.data.db.ProjectRuleEntity
import com.example.tice.xlsx.ProjectColumn

data class ValidationResult(
    val ok: Boolean,
    val message: String = "",
    /** 越界不静默丢弃，需要教师显式确认后保存。 */
    val needConfirm: Boolean = false
)

/** 按表头批注解析出的规则校验成绩。 */
fun validateScore(rule: ProjectRuleEntity, input: String): ValidationResult {
    val value = input.trim()
    if (value.isEmpty()) return ValidationResult(true)
    if (rule.textValue) return ValidationResult(true, "文本成绩，例如 2′20″")

    val number = value.toDoubleOrNull()
        ?: return ValidationResult(false, "请输入数字")
    if (number.isNaN() || number.isInfinite()) return ValidationResult(false, "请输入有效数字")
    if (rule.integerOnly && number % 1.0 != 0.0) {
        return ValidationResult(false, "该项目要求整数")
    }
    val min = rule.minValue
    val max = rule.maxValue
    val rangeText = if (min != null && max != null) "${min.toPlain()} ~ ${max.toPlain()}" else ""
    if (min != null && number < min) {
        return ValidationResult(false, "低于建议范围 $rangeText，确认后仍可保存", true)
    }
    if (max != null && number > max) {
        return ValidationResult(false, "超出建议范围 $rangeText，确认后仍可保存", true)
    }
    return ValidationResult(true)
}

fun validateScore(project: ProjectColumn, input: String): ValidationResult = validateScore(
    ProjectRuleEntity(
        key = project.key,
        column = project.column,
        hint = project.hint,
        minValue = project.min,
        maxValue = project.max,
        integerOnly = project.integerOnly,
        textValue = project.textValue,
        sortOrder = 0
    ),
    input
)

fun ruleHint(rule: ProjectRuleEntity): String {
    val min = rule.minValue
    val max = rule.maxValue
    return when {
        rule.textValue -> "文本成绩，例：2′20″"
        min != null && max != null && rule.integerOnly ->
            "范围 ${min.toPlain()} ~ ${max.toPlain()}，整数"
        min != null && max != null -> "范围 ${min.toPlain()} ~ ${max.toPlain()}"
        rule.integerOnly -> "请填整数"
        else -> "请填数字"
    }
}

private fun Double.toPlain(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString()
