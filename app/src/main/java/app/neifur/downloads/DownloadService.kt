package app.neifur.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import app.neifur.MainActivity
import app.neifur.R
import app.neifur.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps active episode downloads alive when the app is minimized or the screen turns off,
 * posting a quiet, throttled progress notification in the system shade. Stops itself once the queue empties.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        ensureChannel()
        val initial = buildNotification(applicationContext.container.downloads.state.value)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, initial)
        }
        if (intent?.action == ACTION_CANCEL) {
            applicationContext.container.downloads.cancelAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer = scope.launch {
                val nm = getSystemService(NotificationManager::class.java)
                var lastId = -1L
                var lastPct = -1
                var lastQueue = -1
                var lastPostAt = SystemClock.elapsedRealtime()
                applicationContext.container.downloads.state.collect { st ->
                    if (st.runningCount == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                        return@collect
                    }
                    val run = st.currentRunning
                    val id = run?.episodeId ?: -1L
                    val pct = run?.let { (it.progress * 100).toInt().coerceIn(0, 100) } ?: -1
                    val now = SystemClock.elapsedRealtime()
                    val changedItem = id != lastId || st.runningCount != lastQueue
                    // Throttle notifications to <= 1 update/sec and only when percentage advances:
                    // avoids HyperOS / MIUI notification row flickering.
                    val changedPct = pct != lastPct && now - lastPostAt >= 1000L
                    if (changedItem || changedPct) {
                        lastId = id
                        lastPct = pct
                        lastQueue = st.runningCount
                        lastPostAt = now
                        nm.notify(NOTIF_ID, buildNotification(st))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        applicationContext.container.downloads.cancelAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Загрузки серий", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Прогресс скачивания серий для офлайн-просмотра"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun buildNotification(st: DownloadsState): android.app.Notification {
        val run = st.currentRunning
        val title = run?.animeTitle ?: "Подготовка загрузки…"
        val pct = run?.let { (it.progress * 100).toInt().coerceIn(0, 100) } ?: 0
        val extraQueue = (st.runningCount - 1).coerceAtLeast(0)
        // Compact text for HyperOS/MIUI collapsed progress row (which shares one line with the title).
        val shortText = when {
            run == null -> "Очередь: ${st.runningCount}"
            run.totalBytes > 0L -> "${run.episodeLabel} · $pct%"
            run.bytes > 0L -> "${run.episodeLabel} · ${formatBytes(run.bytes)}"
            else -> run.episodeLabel
        }
        val subText = when {
            run != null && run.totalBytes > 0L -> "${formatBytes(run.bytes)} / ${formatBytes(run.totalBytes)}" +
                if (extraQueue > 0) " · ещё $extraQueue" else ""
            extraQueue > 0 -> "Ещё в очереди: $extraQueue"
            else -> null
        }
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_DOWNLOADS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 2,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(0xFF9E7BFF.toInt())
            .setContentTitle(title)
            .setContentText(shortText)
            .apply { if (subText != null) setSubText(subText) }
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setProgress(100, pct, run == null || run.totalBytes <= 0L)
            .setContentIntent(open)
            .addAction(0, "Отменить", cancel)
            .build()
    }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTIF_ID = 366
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
        private const val ACTION_CANCEL = "app.neifur.CANCEL_DOWNLOADS"
    }
}
