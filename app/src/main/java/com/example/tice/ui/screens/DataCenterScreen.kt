package com.example.tice.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.tice.ui.components.AppTopBar
import com.example.tice.ui.nav.navigateFlat
import com.example.tice.ui.vm.TiceViewModel
import com.example.tice.util.exportFileName

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
    ) { granted ->
        if (!granted) vm.fail("未授予通知权限，备份服务的运行状态提示可能不可见")
    }

    val testedClasses = state.classes.filter { it.tested > 0 }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppTopBar("数据与备份", badge = "${state.classes.size} 个班级 · ${state.backupFiles.size} 个备份包")
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ExportSection(
                testedClasses = testedClasses,
                state = state,
                onToggleClass = vm::toggleExportClass,
                onExport = {
                    // 部分设备没有 DocumentsUI / 保存位置组件，launch 会直接抛异常，必须兜住
                    runCatching { createDocument.launch(exportFileName()) }
                        .onFailure { vm.fail("无法打开保存位置：${it.message ?: "请稍后重试"}") }
                },
                onShare = { shareExport(context, state.lastExportUri, vm) }
            )

            BackupServiceSection(
                backup = backup,
                onServiceChange = { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= 33) {
                        requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    vm.setBackupService(enabled)
                },
                onCopyUrl = { clipboard.setText(AnnotatedString(backup.url)) }
            )

            BackupRestoreSection(
                state = state,
                onCreateBackup = vm::createBackup,
                onPickBackup = { pickBackup.launch(arrayOf("application/zip", "*/*")) }
            )

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

/** 分享最近导出的表：URI 已由导出流程生成，这里只负责构造并启动 chooser。 */
private fun shareExport(context: Context, uriString: String, vm: TiceViewModel) {
    runCatching {
        val shareUri = Uri.parse(uriString)
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
}
