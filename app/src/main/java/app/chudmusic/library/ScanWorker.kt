package app.chudmusic.library

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.chudmusic.ChudApp
import app.chudmusic.MainActivity
import app.chudmusic.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Runs one [ScanRequest] as a foreground job with its own notification, so the scan goes on while
 * another app is in front or the screen is off (a plain coroutine there is frozen, then killed).
 * If Android still stops it (process killed, time limit), WorkManager runs it again later, and the
 * scan picks up where it was: what was already written is skipped (see [LocalScanner.isUpToDate]
 * and the size-and-date rule of [NetworkScanner]).
 */
class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val g = (applicationContext as ChudApp).graph
        val req = ScanRequest.fromTags(tags)
        // Refused when the job starts while the app is in the background (Android 12+): the scan
        // then runs as an ordinary job, as the periodic network scan always did.
        runCatching { setForeground(foregroundInfo(null)) }
        val progress = launch {
            g.scanner.state.sample(1000).collect { s ->
                if (s is ScanState.Running) runCatching { setForeground(foregroundInfo(s)) }
            }
        }
        try {
            if (req.local) g.scanner.scanLocal()
            if (req.network) g.scanner.scanNetwork(onlyId = req.onlyId, force = req.force)
        } finally {
            progress.cancel()
        }
        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(s: ScanState.Running?): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.scan_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = s?.label ?: ctx.getString(R.string.src_phone)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(ctx.getString(R.string.scan_notif_title))
            .setContentText(if (s == null) null else ctx.getString(R.string.scan_running, label) + s.counter)
            .setProgress(s?.total ?: 0, s?.done ?: 0, s == null || s.total == 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        private const val CHANNEL = "library-scan"
        private const val NOTIFICATION_ID = 4107
    }
}
