package com.example.tice.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.tice.R
import com.example.tice.TiceApp
import com.example.tice.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class BackupStatus(
    val running: Boolean = false,
    val ip: String = "",
    val port: Int = 0,
    val code: String = "",
    val url: String = "",
    val error: String = ""
)

/** 前台服务保活，通知栏展示电脑可访问的地址与访问码。服务默认关闭，由用户显式开启。 */
class BackupService : Service() {
    companion object {
        private const val TAG = "TiceBackup"
        private const val CHANNEL_ID = "tice_backup"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.tice.backup.START"
        const val ACTION_STOP = "com.example.tice.backup.STOP"

        private val _status = MutableStateFlow(BackupStatus())
        val status: StateFlow<BackupStatus> = _status

        fun start(context: Context) {
            val intent = Intent(context, BackupService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackupService::class.java))
        }
    }

    private var server: BackupHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 要求 startForegroundService 之后必须立刻调用 startForeground
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle("正在启动体测备份服务…")
                .setOngoing(true)
                .build()
        )
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (server != null) return
        val container = (application as TiceApp).container
        val accessCode = (1000..9999).random().toString()
        val http = BackupHttpServer(accessCode, { container.fileStore.backupFiles() }) { message ->
            Log.d(TAG, message)
        }
        val port = try {
            http.start()
        } catch (e: Exception) {
            Log.w(TAG, "HTTP 服务启动失败: ${e.javaClass.simpleName}")
            _status.value = BackupStatus(error = "端口不可用，请关闭其他占用 8080 的应用后重试")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        server = http
        val ip = NetworkUtils.lanIpAddress() ?: "127.0.0.1"
        val url = "http://$ip:$port/?code=$accessCode"
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("体测备份服务运行中")
            .setContentText("$ip:$port · 访问码 $accessCode")
            .setStyle(NotificationCompat.BigTextStyle().bigText("电脑浏览器打开 $url"))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        _status.value = BackupStatus(
            running = true, ip = ip, port = port, code = accessCode, url = url
        )
        Log.d(TAG, "备份服务已启动，端口 $port")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.backup_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.backup_channel_desc) }
            )
        }
    }

    private fun stopEverything() {
        server?.stop()
        server = null
        _status.value = BackupStatus()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
}
