package com.example.tice.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.tice.data.db.StudentEntity
import com.example.tice.ui.components.AppTopBar
import com.example.tice.ui.components.ConfirmDialog
import com.example.tice.ui.components.EmptyHint
import com.example.tice.ui.components.PrimaryButton
import com.example.tice.ui.components.ProgressLine
import com.example.tice.ui.components.SecondaryButton
import com.example.tice.ui.components.SectionCard
import com.example.tice.ui.components.StatBadge
import com.example.tice.ui.nav.navigateFlat
import com.example.tice.ui.vm.ProjectPhase
import com.example.tice.ui.vm.TiceViewModel
import com.example.tice.util.GradeUtil

/**
 * 单个项目的录入页：待测名单与分组准备 → 整组录入 → 本轮完成。
 *
 * 一组可以叫出 1～6 名同学，谁测完就填谁；留空不拦截，但留空的学生仍算「未测」，
 * 下次进入还会出现在名单里。单人模式沿用原来的大卡片视觉。
 */
@Composable
fun TestScreen(vm: TiceViewModel, nav: NavController) {
    val state by vm.state.collectAsState()
    val group = state.activeGroup
    val selected = state.classes.firstOrNull { it.classId in state.selectedClasses }
    val className = selected?.let { GradeUtil.displayClassName(it.className, it.gradeId) }.orEmpty()
    val currentGroup = state.currentGroup

    var confirmClear by remember { mutableStateOf(false) }

    if (state.pendingWarning.isNotEmpty()) {
        ConfirmDialog(
            title = "成绩超出建议范围",
            text = "${state.pendingWarning}\n\n确认后将按当前输入保存本组。",
            confirmText = "仍然保存本组",
            onConfirm = {
                vm.clearPendingWarning()
                vm.nextGroup(forceSave = true)
            },
            onDismiss = { vm.clearPendingWarning() }
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "清空本组成绩",
            text = "将删除本组所有同学当前项目的成绩，这些同学会被记为未测，下次仍会出现在待测名单里。",
            confirmText = "确认清空",
            onConfirm = {
                confirmClear = false
                vm.clearGroup()
            },
            onDismiss = { confirmClear = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        AppTopBar(
            title = group?.title ?: "项目录入",
            badge = when {
                group == null -> "尚未选择项目"
                state.phase == ProjectPhase.Testing && currentGroup.isNotEmpty() ->
                    "$className · 第 ${state.groupNumber}/${state.groupCount} 组 · 本组 ${currentGroup.size} 人"
                else -> className
            },
            trailing = {
                if (group != null) {
                    TextButton(onClick = {
                        if (state.phase == ProjectPhase.Testing) vm.exitProject() else vm.closeProject()
                        nav.navigateFlat("batch")
                    }) {
                        Text(if (state.phase == ProjectPhase.Testing) "退出保存" else "返回")
                    }
                }
            }
        )
        if (state.phase == ProjectPhase.Testing && state.roster.isNotEmpty()) {
            val reached = (state.rosterIndex + currentGroup.size).coerceAtMost(state.roster.size)
            ProgressLine(reached.toFloat() / state.roster.size, "已到第 $reached / ${state.roster.size} 人")
        }

        when {
            group == null -> ScrollableBody {
                EmptyHint("还没有进入任何项目。请到「测试」页选择班级和项目。")
                PrimaryButton(
                    text = "返回测试",
                    onClick = { nav.navigateFlat("batch") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            state.phase == ProjectPhase.Preview -> ScrollableBody {
                PreviewPanel(
                    roster = state.roster,
                    tested = state.activeTested,
                    total = state.classTotal,
                    groupSize = state.groupSize,
                    groupCount = state.groupCount,
                    onGroupSizeChange = vm::setGroupSize,
                    onStart = { vm.startProject() },
                    onEditAll = { vm.startProject(includeAll = true) }
                )
            }

            state.phase == ProjectPhase.Testing -> {
                if (currentGroup.isEmpty()) {
                    ScrollableBody {
                        EmptyHint("名单已处理完，正在返回。")
                        PrimaryButton(
                            text = "返回项目列表",
                            onClick = {
                                vm.closeProject()
                                nav.navigateFlat("batch")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    GroupTestingBody(
                        state = state,
                        group = group,
                        students = currentGroup,
                        onScoreChange = vm::updateScore,
                        onGroupSizeChange = vm::setGroupSize,
                        onPrevious = { vm.previousGroup() },
                        onNext = { vm.nextGroup() },
                        onClear = { confirmClear = true }
                    )
                }
            }

            else -> ScrollableBody {
                DonePanel(
                    title = group.title,
                    tested = state.activeTested,
                    total = state.classTotal,
                    onContinue = { vm.continueUntested() },
                    onBack = {
                        vm.closeProject()
                        nav.navigateFlat("batch")
                    }
                )
            }
        }
    }
}

/** 名单、预览、完成三个阶段共用的可滚动内容区。 */
@Composable
private fun ColumnScope.ScrollableBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        content()
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------- 待测名单与分组准备 ----------------

@Composable
private fun PreviewPanel(
    roster: List<StudentEntity>,
    tested: Int,
    total: Int,
    groupSize: Int,
    groupCount: Int,
    onGroupSizeChange: (Int) -> Unit,
    onStart: () -> Unit,
    onEditAll: () -> Unit
) {
    SectionCard(
        title = "待测名单",
        subtitle = if (roster.isEmpty()) "本项目已全部测完" else "共 ${roster.size} 人，确认后开始录入"
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBadge("待测 ${roster.size} 人")
            StatBadge("已测 $tested / $total")
        }
        Spacer(Modifier.height(16.dp))
        GroupSizeSelector(groupSize, groupCount, onGroupSizeChange)
        if (roster.isNotEmpty() && groupSize > 1) {
            Spacer(Modifier.height(16.dp))
            PreviewRollCall(roster.take(groupSize))
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = "开始测试",
            onClick = onStart,
            enabled = roster.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        )
        if (tested > 0) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = "修改已测成绩",
                onClick = onEditAll,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (roster.isEmpty()) {
                "没有待测学生。可以点「修改已测成绩」逐人核对或改分。"
            } else {
                "留空将记为未测，下次进入项目时会再次出现。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (roster.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            StudentGrid(roster, highlightCount = groupSize)
        }
    }
}

// ---------------- 本轮完成 ----------------

@Composable
private fun DonePanel(
    title: String,
    tested: Int,
    total: Int,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    val remaining = (total - tested).coerceAtLeast(0)
    SectionCard(title = "本轮录入完成", subtitle = title) {
        ProgressLine(
            if (total > 0) tested.toFloat() / total else 0f,
            "已测 $tested / $total"
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (remaining == 0) {
                "本项目已全部测完，可以到「数据与备份」导出成绩。"
            } else {
                "还有 $remaining 人未测（含留空的学生），可以继续录入。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    PrimaryButton(
        text = "返回项目列表",
        onClick = onBack,
        modifier = Modifier.fillMaxWidth()
    )
    if (remaining > 0) {
        SecondaryButton(
            text = "继续测试未测学生",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
