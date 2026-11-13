package com.example.tice.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.tice.data.db.ProjectRuleEntity
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
import com.example.tice.ui.vm.GROUP_SIZE_OPTIONS
import com.example.tice.ui.vm.MAX_GROUP_SIZE
import com.example.tice.ui.vm.MIN_GROUP_SIZE
import com.example.tice.ui.vm.ProjectPhase
import com.example.tice.ui.vm.TiceUiState
import com.example.tice.ui.vm.TiceViewModel
import com.example.tice.util.GradeUtil
import com.example.tice.util.ProjectGroup
import com.example.tice.util.ruleHint
import com.example.tice.util.validateScore
import kotlinx.coroutines.launch

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

/** 开测前把第一组姓名集中放大，老师可照着把同学叫出队伍。 */
@Composable
private fun PreviewRollCall(students: List<StudentEntity>) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "请先叫出这一组",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${students.size} 人",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                students.forEachIndexed { index, student ->
                    RollCallChip(index + 1, student.name, filled = false, focused = false, onClick = null)
                }
            }
        }
    }
}

// ---------------- 整组录入 ----------------

@Composable
private fun ColumnScope.GroupTestingBody(
    state: TiceUiState,
    group: ProjectGroup,
    students: List<StudentEntity>,
    onScoreChange: (String, String, String) -> Unit,
    onGroupSizeChange: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClear: () -> Unit
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val cardOffsets = remember { mutableMapOf<Int, Float>() }
    var focusedIndex by remember(state.rosterIndex) { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.rosterIndex) {
        cardOffsets.clear()
        scrollState.scrollTo(0)
    }

    if (students.size > 1) {
        key(state.rosterIndex) {
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInHorizontally { it / 4 }
            ) {
                RollCallStrip(
                    students = students,
                    scores = state.groupScores,
                    keys = group.keys,
                    focusedIndex = focusedIndex,
                    onFocus = { index ->
                        focusedIndex = index
                        coroutineScope.launch {
                            cardOffsets[index]?.let { y ->
                                scrollState.animateScrollTo(y.toInt().coerceAtLeast(0))
                            }
                        }
                    }
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        GroupSizeSelector(state.groupSize, state.groupCount, onGroupSizeChange)
        if (group.rules.size > 1) {
            Text(
                "${group.rules.joinToString("、") { it.key }}需要全部填写才算完成本项目。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        students.forEachIndexed { index, student ->
            StudentScoreCard(
                student = student,
                number = state.rosterIndex + index + 1,
                rules = group.rules,
                scores = state.groupScores[student.studentId].orEmpty(),
                hero = students.size == 1,
                focused = focusedIndex == index,
                onScoreChange = onScoreChange,
                modifier = Modifier.onGloballyPositioned { coords ->
                    cardOffsets[index] = coords.positionInParent().y
                }
            )
        }
        Text(
            "每次输入即时保存；留空将记为未测，下次进入项目时会再次出现。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }

    BottomActionBar(
        canGoPrevious = state.rosterIndex > 0,
        isLastGroup = state.groupNumber >= state.groupCount,
        anyFilled = students.any { student ->
            group.keys.any { key -> state.groupScores[student.studentId]?.get(key)?.isNotBlank() == true }
        },
        busy = state.busy,
        onPrevious = onPrevious,
        onClear = onClear,
        onNext = onNext
    )
}

@Composable
private fun RollCallStrip(
    students: List<StudentEntity>,
    scores: Map<String, Map<String, String>>,
    keys: List<String>,
    focusedIndex: Int?,
    onFocus: (Int) -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本组点名 · 请先叫出队伍",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "点姓名可定位",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                students.forEachIndexed { index, student ->
                    val filled = keys.isNotEmpty() && keys.all { key ->
                        scores[student.studentId]?.get(key)?.isNotBlank() == true
                    }
                    RollCallChip(index + 1, student.name, filled, focusedIndex == index) { onFocus(index) }
                }
            }
        }
    }
}

@Composable
private fun RollCallChip(
    number: Int,
    name: String,
    filled: Boolean,
    focused: Boolean,
    onClick: (() -> Unit)?
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .heightIn(min = 64.dp)
            .clip(shape)
            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .then(onClick?.let { cb -> Modifier.clickable(onClick = cb) } ?: Modifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (filled) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (filled) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "已填",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            "$number",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StudentScoreCard(
    student: StudentEntity,
    number: Int,
    rules: List<ProjectRuleEntity>,
    scores: Map<String, String>,
    hero: Boolean,
    focused: Boolean,
    onScoreChange: (String, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val filled = rules.isNotEmpty() && rules.all { scores[it.key]?.isNotBlank() == true }
    val container = if (hero) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }
    val onContainer = if (hero) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val shape = MaterialTheme.shapes.large
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = if (hero) 0.dp else 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (hero) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier.size(if (hero) 56.dp else 44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "$number",
                            style = if (hero) {
                                MaterialTheme.typography.headlineSmall
                            } else {
                                MaterialTheme.typography.titleLarge
                            },
                            color = if (hero) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    if (hero) {
                        Text(
                            "当前测试同学",
                            style = MaterialTheme.typography.labelMedium,
                            color = onContainer.copy(alpha = 0.75f)
                        )
                    }
                    Text(
                        student.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = onContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        GradeUtil.displayClassName(student.className, student.gradeId),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.8f)
                    )
                }
                FilledBadge(filled)
            }
            rules.forEach { rule ->
                ScoreField(
                    studentId = student.studentId,
                    rule = rule,
                    value = scores[rule.key].orEmpty(),
                    onScoreChange = onScoreChange
                )
            }
        }
    }
}

@Composable
private fun FilledBadge(filled: Boolean) {
    val color by animateColorAsState(
        targetValue = if (filled) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "filled-badge"
    )
    Surface(shape = MaterialTheme.shapes.small, color = color) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (filled) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(
                if (filled) "已填" else "未填",
                style = MaterialTheme.typography.labelLarge,
                color = if (filled) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun BottomActionBar(
    canGoPrevious: Boolean,
    isLastGroup: Boolean,
    anyFilled: Boolean,
    busy: Boolean,
    onPrevious: () -> Unit,
    onClear: () -> Unit,
    onNext: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = "上一组",
                    onClick = onPrevious,
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    dense = true,
                    enabled = canGoPrevious && !busy,
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = "清空本组",
                    onClick = onClear,
                    dense = true,
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                )
            }
            val label = if (isLastGroup) "保存本组并完成" else "保存本组并下一组"
            if (anyFilled) {
                PrimaryButton(
                    text = label,
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    onClick = onNext,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                SecondaryButton(
                    text = "$label（本组尚未填写）",
                    onClick = onNext,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                "每次输入即时保存，退出后可从「测试」页继续未测学生。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------- 共用组件 ----------------

@Composable
private fun GroupSizeSelector(
    size: Int,
    groupCount: Int,
    onSizeChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("每组叫出几名同学", style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilledTonalIconButton(
                onClick = { onSizeChange(size - 1) },
                enabled = size > MIN_GROUP_SIZE,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "减少一人")
            }
            GROUP_SIZE_OPTIONS.forEach { value ->
                SizeChip(value, size == value) { onSizeChange(value) }
            }
            FilledTonalIconButton(
                onClick = { onSizeChange(size + 1) },
                enabled = size < MAX_GROUP_SIZE,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "增加一人")
            }
        }
        Text(
            "当前 $size 人 / 组 · 共 $groupCount 组 · 自动记住，下次进入仍是这个人数",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RowScope.SizeChip(value: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "$value",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun ScoreField(
    studentId: String,
    rule: ProjectRuleEntity,
    value: String,
    onScoreChange: (String, String, String) -> Unit
) {
    val validation = validateScore(rule, value)
    OutlinedTextField(
        value = value,
        onValueChange = { onScoreChange(studentId, rule.key, it) },
        label = { Text(rule.key) },
        placeholder = { Text(ruleHint(rule)) },
        supportingText = {
            when {
                !validation.ok && validation.needConfirm -> Text(
                    validation.message,
                    color = MaterialTheme.colorScheme.tertiary
                )
                !validation.ok -> Text(
                    validation.message,
                    color = MaterialTheme.colorScheme.error
                )
                value.isBlank() -> Text(
                    "留空将记为未测",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Text(ruleHint(rule))
            }
        },
        isError = !validation.ok && !validation.needConfirm,
        textStyle = MaterialTheme.typography.headlineSmall,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (rule.textValue) KeyboardType.Text else KeyboardType.Decimal
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

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

/** 两列姓名网格，和录入名单保持一致；第一组成员高亮，方便开测前点名。 */
@Composable
private fun StudentGrid(students: List<StudentEntity>, highlightCount: Int) {
    students.chunked(2).forEachIndexed { rowIndex, row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            row.forEachIndexed { columnIndex, student ->
                val index = rowIndex * 2 + columnIndex
                val highlighted = index < highlightCount
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = if (highlighted) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLowest
                        }
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (highlighted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (highlighted) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.size(10.dp))
                        Text(
                            student.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
