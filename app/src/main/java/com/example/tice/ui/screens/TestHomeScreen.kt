package com.example.tice.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.tice.ui.components.AppTopBar
import com.example.tice.ui.components.EmptyHint
import com.example.tice.ui.components.OptionChip
import com.example.tice.ui.components.PrimaryButton
import com.example.tice.ui.components.ProgressLine
import com.example.tice.ui.components.SecondaryButton
import com.example.tice.ui.components.SectionCard
import com.example.tice.ui.nav.navigateFlat
import com.example.tice.ui.vm.ProjectPhase
import com.example.tice.ui.vm.TiceViewModel
import com.example.tice.util.GradeUtil

/**
 * 「测试」页：显示当前班级每个项目的测试情况，点项目进入录入。
 *
 * 完成度按项目单独统计：组内成绩非空的学生数 / 班级人数，留空 = 未测。
 */
@Composable
fun TestHomeScreen(vm: TiceViewModel, nav: NavController) {
    val state by vm.state.collectAsState()
    val selected = state.classes.firstOrNull { it.classId in state.selectedClasses }
    val total = state.classTotal
    val active = state.activeGroup

    LaunchedEffect(selected?.classId, state.showAllProjects) { vm.refreshProgress() }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppTopBar(
            title = "测试",
            badge = when {
                selected == null -> "尚未选择班级"
                else -> "${GradeUtil.displayClassName(selected.className, selected.gradeId)} · $total 人"
            },
            trailing = {
                TextButton(onClick = { nav.navigateFlat("classes") }) { Text("换班级") }
            }
        )
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (selected == null) {
                EmptyHint("还没有选择班级。先到「选班」选一个班，再按项目逐个录入成绩。")
                PrimaryButton(
                    text = "去选班",
                    onClick = { nav.navigateFlat("classes") },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                if (active != null && state.phase != ProjectPhase.None) {
                    SecondaryButton(
                        text = "继续录入：${active.title}",
                        icon = Icons.Filled.PlayArrow,
                        onClick = { nav.navigateFlat("test") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val doneGroups = state.projectGroups.count { group ->
                    total > 0 && (state.projectProgress[group.id] ?: 0) >= total
                }
                SectionCard(
                    title = "测试进度",
                    subtitle = "每个项目单独录入，点开即可开始"
                ) {
                    ProgressLine(
                        if (state.projectGroups.isEmpty()) 0f else doneGroups.toFloat() / state.projectGroups.size,
                        "已完成 $doneGroups / ${state.projectGroups.size} 个项目"
                    )
                }

                state.projectGroups.forEach { group ->
                    ProjectCard(
                        title = group.title,
                        tested = state.projectProgress[group.id] ?: 0,
                        total = total,
                        onClick = {
                            vm.openProject(group)
                            nav.navigateFlat("test")
                        }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OptionChip("显示全部项目", state.showAllProjects) {
                        vm.setShowAllProjects(!state.showAllProjects)
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (state.showAllProjects) "已列出工作簿里的全部项目" else "默认只列本年级项目",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProjectCard(title: String, tested: Int, total: Int, onClick: () -> Unit) {
    val done = total > 0 && tested >= total
    val status = when {
        done -> "已完成"
        tested > 0 -> "进行中"
        else -> "未开始"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (done) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (done) 0.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (done) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (done) {
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = if (done) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            ProgressLine(
                if (total > 0) tested.toFloat() / total else 0f,
                "已测 $tested / $total"
            )
        }
    }
}
