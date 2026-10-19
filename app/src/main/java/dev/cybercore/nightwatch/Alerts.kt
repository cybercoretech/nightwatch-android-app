package dev.cybercore.nightwatch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Fetches new alerts from each host and turns the `warn`/`crit` ones into
 * notifications. Push messages only *trigger* this: their content is never
 * shown, so a forged push can't put words on the screen.
 */
object AlertSync {
    const val CHANNEL_CRIT = "alerts_crit"
    const val CHANNEL_WARN = "alerts_warn"
    const val EXTRA_HOST = "host"
    const val EXTRA_ALERTS = "open_alerts"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CRIT, context.getString(R.string.channel_crit), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_WARN, context.getString(R.string.channel_warn), NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Which alert ids each host has already shown, so nothing is shown twice. */
    private fun seen(context: Context) = context.getSharedPreferences("alerts_seen", Context.MODE_PRIVATE)

    suspend fun sync(context: Context, hostId: String? = null) {
        val store = HostStore(context)
        val hosts = store.list().filter { hostId == null || it.id == hostId }
        for (host in hosts) {
            val api = store.api(host) ?: continue
            val last = seen(context).getLong(host.id, -1L)
            val page = runCatching { api.alerts(after = last.takeIf { it >= 0 }, limit = 50) }.getOrNull() ?: continue
            val newest = page.alerts.maxOfOrNull { it.id } ?: last
            // The first sync for a host only sets the baseline.
            if (last >= 0) {
                page.alerts.filter { !it.acked && it.severity in setOf("warn", "crit") }
                    .sortedBy { it.id }
                    .forEach { notify(context, host, it) }
            }
            if (newest > last || last < 0) seen(context).edit { putLong(host.id, maxOf(newest, 0L)) }
        }
    }

    fun notify(context: Context, host: Host, alert: Alert) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_HOST, host.id)
            .putExtra(EXTRA_ALERTS, true)
        val tap = PendingIntent.getActivity(context, host.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val crit = alert.severity == "crit"
        val n = NotificationCompat.Builder(context, if (crit) CHANNEL_CRIT else CHANNEL_WARN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText("${host.label} · ${alert.source}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(host.label + " · " + alert.source, alert.body).filter { it.isNotBlank() }.joinToString("\n")))
            .setPriority(if (crit) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup("nightwatch-${host.id}")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify("${host.id}:${alert.id}".hashCode(), n) }
    }

    /** Background safety net for missed pushes: every 15 minutes, the Android minimum. */
    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("alert-sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Right now, for a push. */
    fun syncSoon(context: Context, hostId: String) {
        val req = OneTimeWorkRequestBuilder<AlertWorker>()
            .setInputData(workDataOf(EXTRA_HOST to hostId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("alert-sync-$hostId", ExistingWorkPolicy.REPLACE, req)
    }
}

class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AlertSync.sync(applicationContext, inputData.getString(AlertSync.EXTRA_HOST))
        return Result.success()
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, AlertSync.CHANNEL_WARN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.checking_alerts))
            .setSilent(true)
            .build()
        return androidx.work.ForegroundInfo(0x6e77, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
