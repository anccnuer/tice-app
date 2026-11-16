package com.example.tice.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import com.example.tice.data.db.StudentEntity
import com.example.tice.ui.components.PrimaryButton
import com.example.tice.ui.components.SecondaryButton
import com.example.tice.ui.vm.TiceUiState
import com.example.tice.util.ProjectGroup
import kotlinx.coroutines.launch

@Composable
internal fun ColumnScope.GroupTestingBody(
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
internal fun RollCallStrip(
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
internal fun BottomActionBar(
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
