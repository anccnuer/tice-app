package com.example.tice.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.tice.ui.components.AppTopBar
import com.example.tice.ui.components.EmptyHint
import com.example.tice.ui.components.PrimaryButton
import com.example.tice.ui.components.ProgressLine
import com.example.tice.ui.components.SecondaryButton
import com.example.tice.ui.components.SectionCard
import com.example.tice.ui.components.StatBadge
import com.example.tice.ui.nav.navigateFlat
import com.example.tice.ui.vm.TiceViewModel

@Composable
fun ImportScreen(vm: TiceViewModel, nav: NavController) {
    val state by vm.state.collectAsState()
    var url by remember { mutableStateOf("") }
    val pickXlsx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importFromUri(it) }
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.restoreFromUri(it) }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppTopBar(
            title = "体测助手",
            badge = if (state.imported) "已导入 · ${state.studentCount} 名学生 · ${state.projectCount} 个项目" else "尚未导入学生数据"
        )
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.busy) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                )
            }

            SectionCard(
                title = "导入全体体测数据",
                subtitle = "本地 Excel 文件，或输入表格下载地址",
                step = 1
            ) {
                PrimaryButton(
                    text = "本地 Excel 导入",
                    icon = Icons.Filled.Add,
                    onClick = {
                        pickXlsx.launch(
                            arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("网络表格地址（http:// 或 https://）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.downloadProgress in 1..99) {
                    Spacer(Modifier.height(12.dp))
                    ProgressLine(state.downloadProgress / 100f, "下载中 ${state.downloadProgress}%")
                }
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    text = "下载并导入",
                    onClick = { vm.importFromUrl(url) },
                    enabled = !state.busy && url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { vm.importSample() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("载入内置示例表（快速体验完整流程）")
                }
            }

            SectionCard(
                title = "模板与项目概览",
                subtitle = "项目规则自动读取表头批注，无需手工配置",
                step = 2
            ) {
                if (!state.imported) {
                    EmptyHint("导入 Excel 后，这里会显示全部测试项目、数值范围与示例行数。")
                } else {
                    Text(
                        "来源：${state.importSource}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    state.rules.chunked(3).forEach { group ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            group.forEach { StatBadge(it.key) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "提示：50米×8往返跑为 2′20″ 类文本成绩，导出时按文本写入，不改动原表格式。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard(
                title = "开始工作",
                subtitle = "按班级点名、逐人录入、课后导出与备份",
                step = 3
            ) {
                PrimaryButton(
                    text = "选取班级并配置项目",
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    onClick = { nav.navigateFlat("classes") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton(
                    text = "导出与备份中心",
                    onClick = { nav.navigateFlat("center") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { pickBackup.launch(arrayOf("application/zip", "*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("导入备份包恢复数据")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
