package org.avmedia.gshockGoogleSync.h5600

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import org.avmedia.gshockGoogleSync.h5600.AlarmPool.PhoneAlarm
import org.avmedia.gshockGoogleSync.utils.AppTimeFormat
import timber.log.Timber
import javax.inject.Inject

/**
 * One notification per alarm that is snoozing on the watch, with a "Stop snooze" button. On the
 * DW-H5600 a button press while it rings does not end the snooze; only the app (or this
 * notification) stops it, so the snooze runs until the end of its window unless stopped here.
 * The watch does not report snooze state, so these follow the phone's snooze window
 * ([AlarmPool.snoozing]) and disappear when it ends or the snooze is stopped.
 */
object SnoozeNotifier {
    // High importance = shown on screen as a banner (heads-up), not only in the bar. A channel's
    // importance cannot be raised after creation, hence a new id; the old channel is deleted.
    private const val CHANNEL_ID = "h5600_snooze_onscreen"
    private const val OLD_CHANNEL_ID = "h5600_snooze"
    const val ACTION_STOP = "org.avmedia.gshockGoogleSync.h5600.STOP_SNOOZE"
    const val EXTRA_ID = "alarm_id"

    /** Ids of the alarms that currently have a notification (to cancel the ones that ended). */
    private val shown = mutableSetOf<String>()

    private fun notificationId(alarmId: String) = 0x5500 + (alarmId.hashCode() and 0xFFF)

    @Synchronized
    /** [snoozing]: each snoozing alarm with the time its snooze ends. */
    fun update(context: Context, snoozing: List<Pair<PhoneAlarm, java.time.LocalDateTime>>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.deleteNotificationChannel(OLD_CHANNEL_ID)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Alarm snooze (watch)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows while a watch alarm is snoozing; lets you stop the snooze"
                setSound(null, null) // the watch is the alarm; the phone only shows the banner
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        val ids = snoozing.map { it.first.id }.toSet()
        (shown - ids).forEach { nm.cancel(notificationId(it)) }
        shown.retainAll(ids)
        snoozing.filter { it.first.id !in shown }.forEach { (a, until) ->
            val stop = PendingIntent.getBroadcast(
                context, notificationId(a.id),
                Intent(context, SnoozeStopReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, a.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val end = AppTimeFormat.hm(until.hour, until.minute)
            val title = AppTimeFormat.hm(a.hour, a.minute) + (if (a.label.isEmpty()) "" else " · ${a.label}") + " · snoozing until $end"
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText("Repeats every 5 min until $end. Stop it here when you are up.")
                .setPriority(NotificationCompat.PRIORITY_HIGH) // heads-up on older Android versions
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(false)
                .addAction(0, "Stop snooze", stop)
                .build()
            runCatching { nm.notify(notificationId(a.id), n) }.onFailure { Timber.w(it, "snooze notification") }
            shown += a.id
        }
    }

    fun cancel(context: Context, alarmId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(alarmId))
        synchronized(this) { shown -= alarmId }
    }
}

/** "Stop snooze" from the notification. */
@AndroidEntryPoint
class SnoozeStopReceiver : BroadcastReceiver() {
    @Inject lateinit var runner: H5600ScheduleRunner

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SnoozeNotifier.ACTION_STOP) return
        val id = intent.getStringExtra(SnoozeNotifier.EXTRA_ID) ?: return
        SnoozeNotifier.cancel(context, id)
        runner.requestStopSnooze(id) // queued with the other watch work; runs in the scheduler's scope
    }
}
