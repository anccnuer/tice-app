package com.example.tice.ui.vm

import com.example.tice.data.db.ClassStat
import com.example.tice.data.db.ProjectRuleEntity
import com.example.tice.data.db.StudentEntity
import com.example.tice.util.ProjectGroup

/** 项目级录入的三个阶段：看名单 → 分组录入 → 本轮完成。 */
enum class ProjectPhase { None, Preview, Testing, Done }

/** 整组保存前的校验汇总，用于把问题定位到具体同学。 */
data class GroupCheck(
    val blockers: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

data class TiceUiState(
    val imported: Boolean = false,
    val importSource: String = "",
    val studentCount: Int = 0,
    val projectCount: Int = 0,
    val classes: List<ClassStat> = emptyList(),
    /** 班级单选。 */
    val selectedClasses: Set<String> = emptySet(),
    val exportClasses: Set<String> = emptySet(),
    val rules: List<ProjectRuleEntity> = emptyList(),
    /** 测试页是否显示工作簿里的全部项目（默认只显示本年级默认项目）。 */
    val showAllProjects: Boolean = false,
    val projectGroups: List<ProjectGroup> = emptyList(),
    /** 项目组 id → 已测人数（组内项目全部非空才算已测）。 */
    val projectProgress: Map<String, Int> = emptyMap(),
    val classTotal: Int = 0,
    val activeGroup: ProjectGroup? = null,
    val phase: ProjectPhase = ProjectPhase.None,
    val roster: List<StudentEntity> = emptyList(),
    /** 本组首位学生在 roster 中的下标；单人模式下就是当前学生。 */
    val rosterIndex: Int = 0,
    /** 一组叫出几名同学，默认 1 人保持原有单人体验；持久化在 meta("group_size")。 */
    val groupSize: Int = 1,
    /** 本组成绩：学籍号 → (项目名 → 输入值)。只持有当前组，不含全名单。 */
    val groupScores: Map<String, Map<String, String>> = emptyMap(),
    /** 整组保存前的越界确认文案；非空时由录入页弹确认框。 */
    val pendingWarning: String = "",
    val activeTested: Int = 0,
    val message: String = "",
    val error: String = "",
    val busy: Boolean = false,
    val downloadProgress: Int = 0,
    val backupFiles: List<String> = emptyList(),
    val lastExportUri: String = "",
    val lastExportName: String = ""
) {
    /** 本组学生：从 rosterIndex 起最多 groupSize 人。 */
    val currentGroup: List<StudentEntity>
        get() = if (roster.isEmpty() || rosterIndex >= roster.size) emptyList()
        else roster.subList(rosterIndex, (rosterIndex + groupSize).coerceAtMost(roster.size)).toList()

    /** 第几组（1 起），用于顶栏与进度显示。 */
    val groupNumber: Int get() = if (groupSize <= 0) 1 else rosterIndex / groupSize + 1

    /** 共几组。 */
    val groupCount: Int
        get() = if (roster.isEmpty() || groupSize <= 0) 0
        else (roster.size + groupSize - 1) / groupSize
}
