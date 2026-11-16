package com.example.tice.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.tice.backup.BackupStatus
import com.example.tice.data.db.ClassStat
import com.example.tice.ui.components.EmptyHint
import com.example.tice.ui.components.PrimaryButton
import com.example.tice.ui.components.SectionCard
import com.example.tice.ui.components.StatBadge
import com.example.tice.ui.vm.TiceUiState
import com.example.tice.util.GradeUtil

/** 第 1 段：按班级勾选并导出，附带最近一次导出的分享入口。 */
@Composable
internal fun ExportSection(
    testedClasses: List<ClassStat>,
    state: TiceUiState,
    onToggleClass: (String) -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit
) {
    SectionCard(
        title = "导出已上课班级",
        subtitle = "以原始 Excel 为模板，批注、样式与其它信息列原样保留",
        step = 1
    ) {
        if (testedClasses.isEmpty()) {
            EmptyHint("还没有已测班级。完成一批测试后即可按班级导出。")
        } else {
            testedClasses.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = item.classId in state.exportClasses,
                        onCheckedChange = { onToggleClass(item.classId) }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            GradeUtil.displayClassName(item.className, item.gradeId),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "已测 ${item.tested} / ${item.total} 人",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatBadge("${item.tested} 条")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = if (state.busy) "导出中…" else "导出 Excel",
            onClick = onExport,
            enabled = state.exportClasses.isNotEmpty() && state.imported && !state.busy,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.lastExportUri.isNotEmpty()) {
            TextButton(
                onClick = onShare,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("分享最近导出的 ${state.lastExportName}")
            }
        }
    }
}

/** 第 2 段：局域网只读备份服务的开关与访问地址。 */
@Composable
internal fun BackupServiceSection(
    backup: BackupStatus,
    onServiceChange: (Boolean) -> Unit,
    onCopyUrl: () -> Unit
) {
    SectionCard(
        title = "局域网备份服务",
        subtitle = "手机与电脑连同一 WiFi，电脑浏览器只读下载备份包",
        step = 2
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "开启网页服务",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = backup.running,
                onCheckedChange = onServiceChange
            )
        }
        if (backup.running) {
            Spacer(Modifier.height(14.dp))
            Text(
                "电脑访问地址",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                backup.url,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatBadge("访问码 ${backup.code}")
                TextButton(onClick = onCopyUrl) {
                    Text("复制地址")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "网页只提供查看与下载，不提供上传或修改；用完请关闭服务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (backup.error.isNotEmpty()) {
            Text(
                backup.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** 第 3 段：生成备份包与导入恢复。 */
@Composable
internal fun BackupRestoreSection(
    state: TiceUiState,
    onCreateBackup: () -> Unit,
    onPickBackup: () -> Unit
) {
    SectionCard(
        title = "备份包与恢复",
        subtitle = "备份包含原始模板、全部学生与成绩、校验信息",
        step = 3
    ) {
        PrimaryButton(
            text = "生成备份包",
            onClick = onCreateBackup,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        if (state.backupFiles.isEmpty()) {
            Text(
                "暂无备份包",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.backupFiles.take(6).forEach { name ->
                Text("· $name", style = MaterialTheme.typography.bodyMedium)
            }
        }
        TextButton(
            onClick = onPickBackup,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("导入备份包恢复（App 内完成）")
        }
        Text(
            "恢复会覆盖当前数据，恢复前会自动生成一份回滚快照。备份包含学生姓名、学籍号等信息，请勿外传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
