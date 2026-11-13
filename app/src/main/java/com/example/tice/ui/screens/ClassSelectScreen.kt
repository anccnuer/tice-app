package com.example.tice.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.tice.data.db.ClassStat
import com.example.tice.ui.components.AppTopBar
import com.example.tice.ui.components.EmptyHint
import com.example.tice.ui.components.PrimaryButton
import com.example.tice.ui.components.SectionCard
import com.example.tice.ui.nav.navigateFlat
import com.example.tice.ui.vm.TiceViewModel
import com.example.tice.util.GradeUtil

/**
 * 选班页：班级按年级分组，用小按钮单选。
 *
 * 进度不在这里展示——每个项目测得怎么样，统一到「测试」页看。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClassSelectScreen(vm: TiceViewModel, nav: NavController) {
    val state by vm.state.collectAsState()
    val selected = state.classes.firstOrNull { it.classId in state.selectedClasses }
    val grouped = state.classes
        .groupBy { GradeUtil.gradeOf(it.className, it.gradeId) ?: OTHER_GRADE }
        .toSortedMap()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppTopBar(
            title = "选择班级",
            badge = selected?.let { "已选 ${GradeUtil.displayClassName(it.className, it.gradeId)}" }
                ?: "尚未选择班级"
        )
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.classes.isEmpty()) {
                EmptyHint("还没有班级数据，请先在「数据」页导入全体学生 Excel。")
            } else {
                grouped.forEach { (grade, classes) ->
                    SectionCard(
                        title = if (grade == OTHER_GRADE) "其他年级" else "${grade}年级",
                        subtitle = "${classes.size} 个班"
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            classes.forEach { stat ->
                                ClassChip(
                                    stat = stat,
                                    selected = stat.classId in state.selectedClasses,
                                    onClick = { vm.selectClass(stat.classId) }
                                )
                            }
                        }
                    }
                }
            }
            PrimaryButton(
                text = "进入测试",
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = { nav.navigateFlat("batch") },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth()
            )
            if (selected == null && state.classes.isNotEmpty()) {
                Text(
                    "先选一个班级，再按项目逐个录入成绩。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ClassChip(stat: ClassStat, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                GradeUtil.displayClassName(stat.className, stat.gradeId),
                style = MaterialTheme.typography.labelLarge
            )
        }
    )
}

private const val OTHER_GRADE = 99
