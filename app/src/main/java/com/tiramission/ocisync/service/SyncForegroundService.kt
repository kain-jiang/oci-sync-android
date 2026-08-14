package com.tiramission.ocisync.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.tiramission.ocisync.MainActivity
import com.tiramission.ocisync.OciSyncApp
import com.tiramission.ocisync.R
import com.tiramission.ocisync.core.model.PushRequest
import com.tiramission.ocisync.core.model.Stage
import com.tiramission.ocisync.core.model.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 大文件(≥20MB)push 前台服务,见 docs/06-ui-design.md §6。
 *
 * - Android 14+:foregroundServiceType="dataSync"(manifest 声明)
 * - 通知持续显示阶段 + 进度,可取消
 * - 退后台不中断;v1.1 计划:结果经通知反馈 + pull 支持
 */
class SyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transferJob: Job? = null
    private lateinit var syncService: SyncService

    override fun onCreate() {
        super.onCreate()
        syncService = (application as OciSyncApp).container.syncService
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                transferJob?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PUSH -> {
                val localPath = intent.getStringExtra(EXTRA_LOCAL_PATH) ?: run { stopSelf(); return START_NOT_STICKY }
                val ref = intent.getStringExtra(EXTRA_REMOTE_REF) ?: run { stopSelf(); return START_NOT_STICKY }
                startForegroundCompat()
                startPush(File(localPath), ref, intent.getStringExtra(EXTRA_PASSPHRASE))
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(getString(R.string.notif_starting), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startPush(localPath: File, ref: String, passphrase: String?) {
        transferJob = serviceScope.launch {
            val result = syncService.push(
                PushRequest(
                    localPath = localPath,
                    remoteRef = ref,
                    passphrase = passphrase?.ifBlank { null },
                ),
                onStage = { stage -> updateStage(stage) },
                onProgress = { p -> updateProgress(p) },
            )
            notifyResult(
                success = result.isSuccess,
                message = result.exceptionOrNull()?.message,
            )
            stopSelf()
        }
    }

    private fun updateStage(stage: Stage) {
        val label = when (stage) {
            Stage.PACKING -> getString(R.string.stage_packing)
            Stage.ENCRYPTING -> getString(R.string.stage_encrypting)
            Stage.UPLOADING -> getString(R.string.stage_uploading)
            Stage.DONE -> getString(R.string.stage_done)
            else -> getString(R.string.notif_starting)
        }
        notifyTransfer(getString(R.string.notif_title_push), label)
    }

    private fun updateProgress(progress: Float) {
        val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
        notifyTransfer(getString(R.string.notif_title_push), getString(R.string.notif_progress, percent))
    }

    private fun notifyResult(success: Boolean, message: String?) {
        val title = if (success) getString(R.string.notif_success) else getString(R.string.notif_failed)
        val text = if (success) getString(R.string.notif_push_done) else (message ?: getString(R.string.common_error))
        val notification = buildBase(title, text)
            .setContentIntent(launchIntent())
            .setAutoCancel(true)
            .build()
        notifySafely(notification)
    }

    private fun notifyTransfer(title: String, text: String) {
        notifySafely(buildNotification(title, 0, text))
    }

    /** Android 13+ 需 POST_NOTIFICATIONS 权限,未授权时静默跳过通知(服务本身不受影响)。 */
    private fun notifySafely(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, progressPercent: Int, text: String? = null): android.app.Notification {
        val builder = buildBase(title, text ?: title)
        if (progressPercent in 1..99) {
            builder.setProgress(100, progressPercent, false)
        }
        // 取消 action
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SyncForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(0, getString(R.string.push_cancel), cancelIntent)
        return builder.build()
    }

    private fun buildBase(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

    private fun launchIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        transferJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "oci_sync_transfer"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_PUSH = "com.tiramission.ocisync.action.PUSH"
        private const val ACTION_CANCEL = "com.tiramission.ocisync.action.CANCEL"
        private const val EXTRA_LOCAL_PATH = "local_path"
        private const val EXTRA_REMOTE_REF = "remote_ref"
        private const val EXTRA_PASSPHRASE = "passphrase"

        /** 启动前台服务执行 push(大文件专用)。 */
        fun startPush(context: Context, localPath: File, ref: String, passphrase: String?) {
            val intent = Intent(context, SyncForegroundService::class.java)
                .setAction(ACTION_PUSH)
                .putExtra(EXTRA_LOCAL_PATH, localPath.absolutePath)
                .putExtra(EXTRA_REMOTE_REF, ref)
                .putExtra(EXTRA_PASSPHRASE, passphrase)
            context.startForegroundService(intent)
        }
    }
}
