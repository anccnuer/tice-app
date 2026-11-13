package com.example.tice.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.tice.ui.components.AppTopBar
import com.example.tice.ui.components.EmptyHint
import com.example.tice.ui.components.PrimaryButton
import com.example.tice.ui.components.SectionCard
import com.example.tice.ui.components.StatBadge
import com.example.tice.ui.nav.navigateFlat
import com.example.tice.ui.vm.TiceViewModel
import com.example.tice.util.GradeUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

@Composable
fun DataCenterScreen(vm: TiceViewModel, nav: NavController) {
    val state by vm.state.collectAsState()
    val backup by vm.backupStatus.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(XLSX_MIME)
    ) { uri -> uri?.let { vm.exportSelected(it) } }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.restoreFromUri(it) }
    }
    val requestNotification = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val testedClasses = state.classes.filter { it.tested > 0 }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppTopBar("数据与备份", badge = "${state.classes.size} 个班级 · ${state.backupFiles.size} 个备份包")
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                                onCheckedChange = { vm.toggleExportClass(item.classId) }
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
                    onClick = {
                        val title = "体测成绩_" +
                            SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date()) + ".xlsx"
                        // 部分设备没有 DocumentsUI / 保存位置组件，launch 会直接抛异常，必须兜住
                        runCatching { createDocument.launch(title) }
                            .onFailure { vm.fail("无法打开保存位置：${it.message ?: "请稍后重试"}") }
                    },
                    enabled = state.exportClasses.isNotEmpty() && state.imported && !state.busy,
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.lastExportUri.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            runCatching {
                                val shareUri = Uri.parse(state.lastExportUri)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = XLSX_MIME
                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                    // targetSdk 35：经 chooser 转交 FileProvider URI 需显式授权读权限
                                    clipData = ClipData.newUri(context.contentResolver, "导出表", shareUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val chooser = Intent.createChooser(send, "分享导出表").apply {
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                if (chooser.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(chooser)
                                } else {
                                    vm.fail("没有可用于分享的应用，可到「下载」目录直接打开导出表")
                                }
                            }.onFailure { e ->
                                vm.fail("分享失败：${e.message ?: "请到「下载」目录直接打开导出表"}")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("分享最近导出的 ${state.lastExportName}")
                    }
                }
            }

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
                        onCheckedChange = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= 33) {
                                requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            vm.setBackupService(enabled)
                        }
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
                        TextButton(onClick = { clipboard.setText(AnnotatedString(backup.url)) }) {
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

            SectionCard(
                title = "备份包与恢复",
                subtitle = "备份包含原始模板、全部学生与成绩、校验信息",
                step = 3
            ) {
                PrimaryButton(
                    text = "生成备份包",
                    onClick = { vm.createBackup() },
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
                    onClick = { pickBackup.launch(arrayOf("application/zip", "*/*")) },
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

            TextButton(
                onClick = { nav.navigateFlat("import") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("返回首页重新导入数据")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
