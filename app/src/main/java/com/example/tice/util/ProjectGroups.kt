package com.example.tice.util

import com.example.tice.data.db.ProjectRuleEntity

/**
 * 录入单位：身高与体重合并为一个项目组（两个输入框），其余项目各自一组。
 *
 * 一个项目组就是一个「卡片」、一次录入任务；组内所有项目都填写了非空成绩才算该组已测。
 */
data class ProjectGroup(
    val title: String,
    val rules: List<ProjectRuleEntity>
) {
    val keys: List<String> get() = rules.map { it.key }

    /** 稳定标识，用于统计进度。 */
    val id: String get() = keys.joinToString("|")
}

fun groupProjects(rules: List<ProjectRuleEntity>): List<ProjectGroup> {
    if (rules.isEmpty()) return emptyList()
    val bodyRules = rules.filter { it.isBodyMetric() }
    val groups = mutableListOf<ProjectGroup>()
    var bodyAdded = false
    rules.forEach { rule ->
        if (rule.isBodyMetric()) {
            if (!bodyAdded) {
                bodyAdded = true
                groups += ProjectGroup(
                    title = bodyRules.joinToString(" / ") { it.key },
                    rules = bodyRules
                )
            }
        } else {
            groups += ProjectGroup(title = rule.key, rules = listOf(rule))
        }
    }
    return groups
}

private fun ProjectRuleEntity.isBodyMetric(): Boolean =
    key.contains("身高") || key.contains("体重")
