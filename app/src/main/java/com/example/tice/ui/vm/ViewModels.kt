package com.example.tice.ui.vm

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tice.TiceApp
import com.example.tice.backup.BackupService
import com.example.tice.backup.BackupStatus
import com.example.tice.data.db.ClassStat
import com.example.tice.data.db.ProjectRuleEntity
import com.example.tice.data.db.StudentEntity
import com.example.tice.util.GradeUtil
import com.example.tice.xlsx.XlsxCodec
import com.example.tice.util.ProjectGroup
import com.example.tice.util.groupProjects
import com.example.tice.util.validateScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/** 项目级录入的三个阶段：看名单 → 分组录入 → 本轮完成。 */
enum class ProjectPhase { None, Preview, Testing, Done }

/** 每组人数的快捷候选值；加减微调可落到区间内任意整数。 */
val GROUP_SIZE_OPTIONS = listOf(1, 2, 3, 4, 6)
const val MIN_GROUP_SIZE = 1
const val MAX_GROUP_SIZE = 6

/** 取不到 ActivityManager 时按 64MB 单进程 heap 估算。 */
private const val DEFAULT_MEMORY_CLASS_MB = 64
/** 工作表体积预算下限，避免正常的学校上报表被误拒。 */
private const val MIN_SHEET_BUDGET_BYTES = 2L * 1024 * 1024

/** 整组保存前的校验汇总，用于把问题定位到具体同学。 */
data class GroupCheck(
    val blockers: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

/** 导出完成后的轻量摘要，避免把整张工作表字符串带出 IO 协程继续占内存。 */
private data class ExportDone(
    val name: String,
    val share: String,
    val keptStudents: Int,
    val writtenCells: Int
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

class TiceViewModel(app: Application) : AndroidViewModel(app) {
    private val application: Application = getApplication()
    private val container = (app as TiceApp).container
    private val _state = MutableStateFlow(TiceUiState())
    val state: StateFlow<TiceUiState> = _state.asStateFlow()
    val backupStatus: StateFlow<BackupStatus> = BackupService.status

    /** 整组保存的一次性标志，防止连点「保存本组并下一组」造成跳组。 */
    private var savingGroup = false

    init {
        viewModelScope.launch {
            container.testRepository.classStats().collect { list ->
                _state.update { it.copy(classes = list) }
                // 成绩变化后 classStats 会重新发射，这里同步刷新项目进度
                if (_state.value.selectedClasses.isNotEmpty()) refreshProgress()
            }
        }
        viewModelScope.launch {
            container.testRepository.rules().collect { rules ->
                _state.update { it.copy(rules = rules, projectCount = rules.size) }
                refreshProgress()
            }
        }
        refresh()
        viewModelScope.launch {
            _state.update { it.copy(groupSize = container.testRepository.savedGroupSize()) }
        }
    }

    // ---------------- 导入 ----------------
    fun importFromUri(uri: Uri) = viewModelScope.launch {
        busy(true)
        runCatching { container.workbookRepository.importFromUri(application, uri) }
            .onSuccess {
                refresh()
                notify("导入成功：${it.studentCount} 名学生 · ${it.classCount} 个班级 · ${it.projectCount} 个项目")
            }
            .onFailure { fail("导入失败：${it.message}") }
        busy(false)
    }

    fun importFromUrl(url: String) = viewModelScope.launch {
        busy(true)
        _state.update { it.copy(downloadProgress = 0) }
        runCatching {
            container.workbookRepository.importFromUrl(url) { progress ->
                _state.update { it.copy(downloadProgress = progress) }
            }
        }
            .onSuccess {
                refresh()
                notify("下载并导入成功：${it.studentCount} 名学生 · ${it.projectCount} 个项目")
            }
            .onFailure { fail("网络导入失败：${it.message}") }
        busy(false)
    }

    fun importSample() = viewModelScope.launch {
        busy(true)
        runCatching { container.workbookRepository.importFromAssets(application) }
            .onSuccess {
                refresh()
                notify("已载入内置示例表：${it.studentCount} 名学生，可先体验完整流程")
            }
            .onFailure { fail("示例表载入失败：${it.message}") }
        busy(false)
    }

    // ---------------- 选班 ----------------
    /** 选择一个班级。班级列表为单选；切换班级会结束当前项目的录入现场（成绩已保存）。 */
    fun selectClass(classId: String) {
        val s = _state.value
        if (classId in s.selectedClasses) return
        _state.update {
            it.copy(
                selectedClasses = setOf(classId),
                activeGroup = null,
                phase = ProjectPhase.None,
                roster = emptyList(),
                rosterIndex = 0,
                groupScores = emptyMap(),
                pendingWarning = "",
                activeTested = 0
            )
        }
        refreshProgress()
    }

    fun toggleExportClass(classId: String) = _state.update { s ->
        s.copy(exportClasses = if (classId in s.exportClasses) s.exportClasses - classId else s.exportClasses + classId)
    }

    fun setShowAllProjects(show: Boolean) {
        _state.update { it.copy(showAllProjects = show) }
        refreshProgress()
    }

    // ---------------- 项目进度 ----------------
    /**
     * 重新计算当前班级每个项目组的进度。
     *
     * 「已测」= 组内所有项目都有非空成绩；留空的学生会一直留在待测名单里。
     */
    fun refreshProgress() = viewModelScope.launch {
        val s = _state.value
        val classId = s.selectedClasses.firstOrNull()
        if (classId == null) {
            _state.update { it.copy(projectGroups = emptyList(), projectProgress = emptyMap(), classTotal = 0) }
            return@launch
        }
        val groups = groupsFor(s)
        val progress = groups.associate { group ->
            group.id to container.testRepository.testedCount(classId, group.keys)
        }
        val total = s.classes.firstOrNull { it.classId == classId }?.total ?: 0
        // 查询期间可能已经切换班级，避免把旧结果写到新班级上
        if (_state.value.selectedClasses.firstOrNull() != classId) return@launch
        _state.update {
            it.copy(projectGroups = groups, projectProgress = progress, classTotal = total)
        }
    }

    private fun groupsFor(s: TiceUiState): List<ProjectGroup> {
        val classStat = s.classes.firstOrNull { it.classId in s.selectedClasses }
            ?: return groupProjects(s.rules)
        val all = s.rules.map { it.key }
        val keys = if (s.showAllProjects) all.toSet() else defaultProjectKeys(classStat.gradeId, all)
        return groupProjects(s.rules.filter { it.key in keys })
    }

    /** 按年级编号给出默认测试项目，决定「测试」页默认列出哪些项目。 */
    private fun defaultProjectKeys(gradeId: String, all: List<String>): Set<String> {
        fun pick(names: List<String>): Set<String> {
            val matched = names.filter { it in all }.toSet()
            return if (matched.isEmpty()) all.toSet() else matched
        }
        val level = gradeId.takeLast(1).toIntOrNull()
        return when {
            gradeId.startsWith("1") && level != null && level <= 2 ->
                pick(listOf("身高", "体重", "肺活量", "50米跑", "坐位体前屈", "一分钟跳绳"))
            gradeId.startsWith("1") && level != null && level <= 4 ->
                pick(listOf("身高", "体重", "肺活量", "50米跑", "坐位体前屈", "一分钟跳绳", "一分钟仰卧起坐"))
            gradeId.startsWith("1") -> all.toSet()
            else -> pick(listOf("身高", "体重", "肺活量", "50米跑", "坐位体前屈", "一分钟仰卧起坐"))
        }
    }

    // ---------------- 项目录入 ----------------
    /** 点击项目卡片：先展示待测名单，确认后才进入录入。 */
    fun openProject(group: ProjectGroup) {
        val classId = _state.value.selectedClasses.firstOrNull()
        if (classId == null) {
            fail("请先选择一个班级")
            return
        }
        _state.update {
            it.copy(
                activeGroup = group,
                phase = ProjectPhase.Preview,
                roster = emptyList(),
                rosterIndex = 0,
                groupScores = emptyMap(),
                pendingWarning = "",
                activeTested = 0
            )
        }
        viewModelScope.launch {
            val roster = container.testRepository.untestedStudents(classId, group.keys)
            val tested = container.testRepository.testedCount(classId, group.keys)
            if (_state.value.activeGroup?.id != group.id) return@launch
            _state.update { it.copy(roster = roster, activeTested = tested) }
        }
    }

    /** 开始录入；[includeAll] 为 true 时带上已测学生，用于核对、修改成绩。 */
    fun startProject(includeAll: Boolean = false) = viewModelScope.launch {
        val s = _state.value
        val group = s.activeGroup ?: return@launch
        val classId = s.selectedClasses.firstOrNull() ?: return@launch
        val roster = if (includeAll) {
            container.testRepository.studentsOfClass(classId)
        } else {
            container.testRepository.untestedStudents(classId, group.keys)
        }
        if (roster.isEmpty()) {
            notify("本项目已全部测完，可点「修改已测成绩」核对")
            return@launch
        }
        _state.update {
            it.copy(
                roster = roster,
                rosterIndex = 0,
                groupScores = emptyMap(),
                pendingWarning = "",
                phase = ProjectPhase.Testing
            )
        }
        loadScoresForGroup()
    }

    /** 调整每组人数：先落库本组，再从同一位置按新人数重载，避免丢分或跳组。 */
    fun setGroupSize(size: Int) = viewModelScope.launch {
        val next = size.coerceIn(MIN_GROUP_SIZE, MAX_GROUP_SIZE)
        val s = _state.value
        if (next == s.groupSize) return@launch
        // 只有录入阶段才有成绩需要保护；名单页的 roster 是待测学生，落库会误删他们已有的部分成绩
        val testing = s.phase == ProjectPhase.Testing
        if (testing) persistGroupScores(s)
        container.testRepository.saveGroupSize(next)
        _state.update { it.copy(groupSize = next, pendingWarning = "") }
        if (_state.value.phase == ProjectPhase.Testing) loadScoresForGroup()
    }

    /** 每次输入立即落库，杀进程也不丢数据；只写当前这一名同学。 */
    fun updateScore(studentId: String, key: String, value: String) {
        val s = _state.value
        val keys = s.activeGroup?.keys ?: return
        val studentScores = s.groupScores[studentId].orEmpty() + (key to value)
        _state.update { it.copy(groupScores = it.groupScores + (studentId to studentScores)) }
        viewModelScope.launch {
            runCatching { container.testRepository.saveScores(studentId, keys, studentScores) }
                .onFailure { if (it !is CancellationException) fail("成绩保存失败：${it.message}") }
        }
    }

    /** 清空本组成绩：即时落库语义下等同于删除这些同学本项目的成绩行。 */
    fun clearGroup() = viewModelScope.launch {
        val s = _state.value
        val keys = s.activeGroup?.keys ?: return@launch
        val ids = s.currentGroup.map { it.studentId }
        if (ids.isEmpty()) return@launch
        ids.forEach { id -> container.testRepository.saveScores(id, keys, emptyMap()) }
        _state.update { it.copy(groupScores = it.groupScores - ids.toSet(), pendingWarning = "") }
    }

    /** 整组保存前的校验：硬性错误阻止保存，越界成绩汇总成确认文案；留空是合法输入。 */
    private fun checkGroup(s: TiceUiState, group: ProjectGroup): GroupCheck {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        s.currentGroup.forEach { student ->
            val values = s.groupScores[student.studentId].orEmpty()
            group.rules.forEach { rule ->
                val result = validateScore(rule, values[rule.key].orEmpty())
                when {
                    !result.ok && result.needConfirm -> warnings += "${student.name} ${rule.key}：${result.message}"
                    !result.ok -> blockers += "${student.name} ${rule.key}：${result.message}"
                }
            }
        }
        return GroupCheck(blockers, warnings)
    }

    /**
     * 校验并保存整组，然后前进到下一组；[forceSave] 为 true 表示教师已确认越界成绩。
     * [savingGroup] 防止连点造成的协程交错与跳组。
     */
    fun nextGroup(forceSave: Boolean = false) = viewModelScope.launch {
        if (savingGroup) return@launch
        val s = _state.value
        val group = s.activeGroup ?: return@launch
        if (s.currentGroup.isEmpty()) return@launch
        if (!forceSave) {
            val check = checkGroup(s, group)
            if (check.blockers.isNotEmpty()) {
                fail(check.blockers.first())
                return@launch
            }
            if (check.warnings.isNotEmpty()) {
                _state.update { it.copy(pendingWarning = check.warnings.joinToString("\n")) }
                return@launch
            }
        }
        savingGroup = true
        try {
            persistGroupScores(s)
            val next = (s.rosterIndex + s.groupSize).coerceAtMost(s.roster.size)
            if (next >= s.roster.size) {
                val classId = s.selectedClasses.firstOrNull()
                val tested = if (classId == null) s.activeTested
                else container.testRepository.testedCount(classId, group.keys)
                _state.update {
                    it.copy(
                        phase = ProjectPhase.Done,
                        rosterIndex = s.roster.size,
                        groupScores = emptyMap(),
                        pendingWarning = "",
                        activeTested = tested
                    )
                }
                refreshProgress()
            } else {
                _state.update {
                    it.copy(rosterIndex = next, groupScores = emptyMap(), pendingWarning = "")
                }
                loadScoresForGroup()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("保存本组失败：${e.message}")
        } finally {
            savingGroup = false
        }
    }

    /** 回到上一组；按当前人数回退，不越过名单开头。 */
    fun previousGroup() = viewModelScope.launch {
        val s = _state.value
        if (s.rosterIndex <= 0) return@launch
        persistGroupScores(s)
        val previous = (s.rosterIndex - s.groupSize).coerceAtLeast(0)
        _state.update {
            it.copy(rosterIndex = previous, groupScores = emptyMap(), pendingWarning = "")
        }
        loadScoresForGroup()
    }

    /** 本轮录完后仍有人留空/未到，重新生成待测名单继续测。 */
    fun continueUntested() = viewModelScope.launch {
        val s = _state.value
        val group = s.activeGroup ?: return@launch
        val classId = s.selectedClasses.firstOrNull() ?: return@launch
        val roster = container.testRepository.untestedStudents(classId, group.keys)
        val tested = container.testRepository.testedCount(classId, group.keys)
        if (roster.isEmpty()) {
            _state.update { it.copy(phase = ProjectPhase.Done, roster = emptyList(), activeTested = tested) }
            notify("本项目已全部测完")
            refreshProgress()
            return@launch
        }
        _state.update {
            it.copy(
                roster = roster,
                rosterIndex = 0,
                groupScores = emptyMap(),
                pendingWarning = "",
                phase = ProjectPhase.Testing,
                activeTested = tested
            )
        }
        loadScoresForGroup()
    }

    fun clearPendingWarning() = _state.update { it.copy(pendingWarning = "") }

    /** 保存当前输入并回到项目列表。成绩已落库，下次进入只会看到仍未测的学生。 */
    fun exitProject() {
        val snapshot = _state.value
        viewModelScope.launch { persistGroupScores(snapshot) }
        closeProject()
        notify("进度已保存")
    }

    fun closeProject() {
        _state.update {
            it.copy(
                activeGroup = null,
                phase = ProjectPhase.None,
                roster = emptyList(),
                rosterIndex = 0,
                groupScores = emptyMap(),
                pendingWarning = "",
                activeTested = 0
            )
        }
        refreshProgress()
    }

    /** 用状态快照把本组已输入的成绩写回，避免 closeProject 清空状态后读不到。 */
    private suspend fun persistGroupScores(snapshot: TiceUiState) {
        val keys = snapshot.activeGroup?.keys ?: return
        if (keys.isEmpty()) return
        val scores = snapshot.groupScores
        snapshot.currentGroup.forEach { student ->
            container.testRepository.saveScores(student.studentId, keys, scores[student.studentId].orEmpty())
        }
    }

    /** 一次查询载入本组成绩，并只保留当前项目的列。 */
    private suspend fun loadScoresForGroup() {
        val s = _state.value
        val keys = s.activeGroup?.keys ?: return
        val ids = s.currentGroup.map { it.studentId }
        if (ids.isEmpty()) {
            _state.update { it.copy(groupScores = emptyMap()) }
            return
        }
        val all = container.testRepository.scoresForStudents(ids)
        val filtered = ids.associateWith { id -> all[id].orEmpty().filterKeys { key -> key in keys } }
        _state.update { it.copy(groupScores = filtered) }
    }

    // ---------------- 导出 ----------------
    /**
     * 导出全链路放在 IO：模板解压、工作表改写、zip 写出与 SAF 拷贝都不再压在主线程，
     * 避免大表触发 ANR 或内存 Peak 被系统杀进程（表现为无提示闪退回桌面）。
     */
    fun exportSelected(uri: Uri) = viewModelScope.launch {
        busy(true)
        var generated: File? = null
        try {
            val done = withContext(Dispatchers.IO) {
                val s = _state.value
                val label = s.exportClasses.joinToString("_") { id ->
                    s.classes.firstOrNull { it.classId == id }
                        ?.let { GradeUtil.displayClassName(it.className, it.gradeId) } ?: id
                }.ifEmpty { "已测班级" }.take(40)
                val outcome = container.exportRepository.export(s.exportClasses, label, exportBudgetBytes())
                generated = outcome.file
                container.exportRepository.writeToUri(application, uri, outcome.file)
                // 分享 URI 只是锦上添花：文件已成功写出，不能因它失败而把导出判为失败
                val share = runCatching {
                    container.exportRepository.shareUri(application, outcome.file).toString()
                }.getOrDefault("")
                ExportDone(outcome.file.name, share, outcome.result.keptStudents, outcome.result.writtenCells)
            }
            _state.update { it.copy(lastExportUri = done.share, lastExportName = done.name) }
            refresh()
            notify("已导出 ${done.name}：${done.keptStudents} 名学生、${done.writtenCells} 个成绩单元格，格式与原始表一致")
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            generated?.delete()
            fail("表格过大、手机内存不足。请一次只勾选一个班分批导出，或精简原始模板后重新导入")
        } catch (e: Throwable) {
            fail("导出失败：${exportErrorMessage(e)}")
        } finally {
            busy(false)
        }
    }

    /**
     * 按设备可用内存给出工作表解压体积预算：取「默认上限 / 单进程 heap 的 1/8 / 系统可用内存的 1/8」
     * 三者最小值，并保底 2MB。真流式后常驻内存已是单行缓冲，该预算用于在读取前拒绝异常大的表，
     * 避免低内存设备上系统直接 lowmemorykiller 杀掉前台进程（没有异常可捕获）。
     */
    private fun exportBudgetBytes(): Long {
        val manager = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        val heapBudget = (manager?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB).toLong() * 1024L * 1024L / 8L
        val availBudget = if (memoryInfo.availMem > 0) memoryInfo.availMem / 8L else Long.MAX_VALUE
        return maxOf(
            MIN_SHEET_BUDGET_BYTES,
            minOf(XlsxCodec.MAX_SHEET_BYTES.toLong(), heapBudget, availBudget)
        )
    }

    /** 把导出异常翻译成教师能直接照做的提示，不暴露堆栈与工作表内容。 */
    private fun exportErrorMessage(e: Throwable): String = when {
        e is SecurityException -> "没有保存权限，请改存到「下载」目录后重试"
        e is FileNotFoundException -> "保存位置不可写，请改存到「下载」目录后重试"
        e.message?.contains("工作表解压后超过") == true ->
            "表格过大，请一次只勾选一个班分批导出，或精简原始模板后重新导入"
        e.message.isNullOrBlank() -> e.javaClass.simpleName
        else -> e.message.orEmpty().take(120)
    }

    // ---------------- 备份服务与恢复 ----------------
    fun createBackup() = viewModelScope.launch {
        busy(true)
        runCatching { container.backupManager.createBackupZip() }
            .onSuccess { refresh(); notify("备份包已生成：${it.name}，电脑可在同一 WiFi 下载") }
            .onFailure { fail("生成备份失败：${it.message}") }
        busy(false)
    }

    fun restoreFromUri(uri: Uri) = viewModelScope.launch {
        busy(true)
        runCatching {
            application.contentResolver.openInputStream(uri)?.use { input ->
                container.backupManager.restore(input)
            } ?: throw IllegalArgumentException("无法读取备份文件")
        }.onSuccess {
            refresh()
            // 恢复会清空 meta，分组人数回到备份包里的设置（无则默认单人）
            _state.update { it.copy(groupSize = container.testRepository.savedGroupSize()) }
            notify("恢复完成：${it.studentCount} 名学生、${it.scoreCount} 条成绩（恢复前已保留回滚快照）")
        }.onFailure { fail("恢复失败：${it.message}") }
        busy(false)
    }

    fun setBackupService(enabled: Boolean) {
        if (enabled) BackupService.start(application) else BackupService.stop(application)
    }

    // ---------------- 状态提示 ----------------
    fun notify(text: String) = _state.update { it.copy(message = text, error = "") }
    fun fail(text: String) = _state.update { it.copy(error = text, message = "") }
    fun clearNotice() = _state.update { it.copy(message = "", error = "") }

    private fun busy(value: Boolean) = _state.update { it.copy(busy = value) }

    private fun refresh() {
        _state.update {
            it.copy(
                imported = container.fileStore.hasTemplate(),
                backupFiles = container.fileStore.backupFiles().map { file -> file.name }
            )
        }
        viewModelScope.launch {
            val meta = container.workbookRepository.metaMap()
            _state.update { s ->
                s.copy(
                    importSource = meta["source"].orEmpty(),
                    studentCount = meta["student_count"]?.toIntOrNull() ?: s.classes.sumOf { it.total }
                )
            }
            refreshProgress()
        }
    }
}
