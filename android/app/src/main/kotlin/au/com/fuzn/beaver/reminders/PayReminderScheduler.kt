package au.com.fuzn.beaver.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.data.AppSettings
import au.com.fuzn.beaver.ui.common.FigureFormatter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The reminder toggle lives in SharedPreferences, not the database, so backups and demo loads do not carry it. */
class ReminderPrefs(private val prefs: SharedPreferences) {
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    companion object {
        const val FILE_NAME = "beaver_prefs"
        const val KEY_ENABLED = "payReminderEnabled"

        fun of(context: Context) =
            ReminderPrefs(context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))
    }
}

/** What the scheduler needs from the platform, so it can be tested with a fake. */
interface ReminderBackend {
    /** Replaces every pending payday-eve reminder with [reminders]. */
    fun replace(reminders: List<PayReminder>, zone: ZoneId, now: Instant)

    fun cancelAll()

    /** True when a posted notification would be shown (the notification permission is granted on Android 13+). */
    fun canNotify(): Boolean
}

/**
 * Local notifications through WorkManager one-time work. WorkManager keeps the work across reboots, needs no
 * exact-alarm permission, and may deliver a little late (inexact) which suits a reminder. Nothing leaves the device.
 */
class WorkManagerReminderBackend(context: Context) : ReminderBackend {
    private val context = context.applicationContext

    private val work get() = WorkManager.getInstance(context)

    override fun replace(reminders: List<PayReminder>, zone: ZoneId, now: Instant) {
        work.cancelAllWorkByTag(TAG)
        for (r in reminders) {
            val delay = Duration.between(now, r.fireInstant(zone))
            if (delay.isNegative) continue
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay)
                .addTag(TAG)
                .setInputData(
                    Data.Builder()
                        .putString(ReminderWorker.KEY_TITLE, r.title)
                        .putString(ReminderWorker.KEY_BODY, r.body)
                        .putInt(ReminderWorker.KEY_ID, r.payday.toEpochDay().toInt())
                        .build(),
                )
                .build()
            work.enqueueUniqueWork(r.identifier, ExistingWorkPolicy.REPLACE, request)
        }
    }

    override fun cancelAll() {
        work.cancelAllWorkByTag(TAG)
    }

    override fun canNotify(): Boolean = notificationsAllowed(context)

    companion object {
        const val TAG = "beaver.payday-eve"
    }
}

internal fun notificationsAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/** Posts one reminder when its work runs. */
class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        if (!notificationsAllowed(context)) return Result.success()
        val title = inputData.getString(KEY_TITLE) ?: return Result.success()
        val body = inputData.getString(KEY_BODY) ?: return Result.success()
        val id = inputData.getInt(KEY_ID, 0)

        val manager = context.getSystemService(NotificationManager::class.java) ?: return Result.success()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Payday reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A reminder the evening before each payday."
            },
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "payday-eve"
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"
        const val KEY_ID = "id"
    }
}

/** Turns the reminder on or off and keeps the scheduled notifications in step with the forecast. */
class PayReminderScheduler(
    private val prefs: ReminderPrefs,
    private val backend: ReminderBackend,
    private val clock: () -> Instant = { Instant.now() },
) {
    val isEnabled: Boolean get() = prefs.enabled

    /** Call once the notification permission is held (Android 13+: after the user allowed it). */
    fun enable(forecast: Forecast, settings: AppSettings) {
        prefs.enabled = true
        reschedule(forecast, settings)
    }

    fun disable() {
        prefs.enabled = false
        backend.cancelAll()
    }

    /** Replaces the pending reminders from the current forecast. Does nothing while the reminder is off. */
    fun reschedule(forecast: Forecast, settings: AppSettings) {
        if (!prefs.enabled) return
        if (!backend.canNotify()) return
        val now = clock()
        val local = now.atZone(settings.timeZone)
        val minutes = local.hour * 60 + local.minute
        val fmt = FigureFormatter(settings.currency)
        // The forecast's "today" is computed in the settings zone, so it matches `local`'s date.
        val reminders = PayReminderPlanner.reminders(forecast, minutes, fmt::string)
        backend.replace(reminders, settings.timeZone, now)
    }

    companion object {
        fun of(context: Context) = PayReminderScheduler(ReminderPrefs.of(context), WorkManagerReminderBackend(context))
    }
}
