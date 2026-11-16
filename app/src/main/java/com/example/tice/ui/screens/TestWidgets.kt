package com.example.tice.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tice.data.db.ProjectRuleEntity
import com.example.tice.data.db.StudentEntity
import com.example.tice.ui.components.RemoveIcon
import com.example.tice.util.GROUP_SIZE_OPTIONS
import com.example.tice.util.GradeUtil
import com.example.tice.util.MAX_GROUP_SIZE
import com.example.tice.util.MIN_GROUP_SIZE
import com.example.tice.util.ruleHint
import com.example.tice.util.validateScore

/** 开测前把第一组姓名集中放大，老师可照着把同学叫出队伍。 */
@Composable
internal fun PreviewRollCall(students: List<StudentEntity>) {
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

@Composable
internal fun RollCallChip(
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
internal fun StudentScoreCard(
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
internal fun FilledBadge(filled: Boolean) {
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
internal fun GroupSizeSelector(
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
                Icon(RemoveIcon, contentDescription = "减少一人")
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
internal fun RowScope.SizeChip(value: Int, selected: Boolean, onClick: () -> Unit) {
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
internal fun ScoreField(
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

/** 两列姓名网格，和录入名单保持一致；第一组成员高亮，方便开测前点名。 */
@Composable
internal fun StudentGrid(students: List<StudentEntity>, highlightCount: Int) {
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
