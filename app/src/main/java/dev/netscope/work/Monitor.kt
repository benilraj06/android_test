package dev.netscope.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.netscope.MainActivity
import dev.netscope.R
import dev.netscope.container
import dev.netscope.net.NetDevice
import java.util.concurrent.TimeUnit

class MonitorWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val outcome = runCatching { applicationContext.container.scanController.performScan(ui = false) }.getOrNull()
            ?: return Result.success()
        if (outcome.newDevices.isNotEmpty()) {
            Notifications.notifyNewDevices(applicationContext, outcome.newDevices, outcome.ssid)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "netscope-monitor"

        fun schedule(context: Context, minutes: Int) {
            val request = PeriodicWorkRequestBuilder<MonitorWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}

object Notifications {
    const val CHANNEL_MONITOR = "monitor"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_MONITOR, "New device alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when an unknown device joins your Wi-Fi"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun notifyNewDevices(context: Context, devices: List<NetDevice>, ssid: String?) {
        if (!canNotify(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, "devices")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val where = ssid?.let { " on $it" } ?: ""
        val title = if (devices.size == 1) "New device joined$where" else "${devices.size} new devices joined$where"
        val text = devices.joinToString { "${it.displayName} (${it.ip})" }
        val n = NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1001, n)
    }
}
