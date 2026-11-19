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
 * One notification per snoozing alarm, with a "Stop snooze" button. The snooze is the phone's
 * ([AlarmPool.snoozing]): the alarm rings again every few minutes, without end, until it is stopped
 * here or on the Alarms tab. A button press on the watch only silences one ring.
 * The text follows the next repeat time; it is posted again (silently) when that changes, so a
 * notification swiped away comes back after the next ring.
 */
object SnoozeNotifier {
    // High importance = shown on screen as a banner (heads-up), not only in the bar. A channel's
    // importance cannot be raised after creation, hence a new id; the old channel is deleted.
    private const val CHANNEL_ID = "h5600_snooze_onscreen"
    private const val OLD_CHANNEL_ID = "h5600_snooze"
    const val ACTION_STOP = "org.avmedia.gshockGoogleSync.h5600.STOP_SNOOZE"
    const val EXTRA_ID = "alarm_id"

    /** Alarm id -> text of its notification now (to cancel ended ones and skip unchanged ones). */
    private val shown = mutableMapOf<String, String>()

    private fun notificationId(alarmId: String) = 0x5500 + (alarmId.hashCode() and 0xFFF)

    /** [snoozing]: each snoozing alarm with its next repeat time. */
    @Synchronized
    fun update(context: Context, snoozing: List<Pair<PhoneAlarm, java.time.LocalDateTime>>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.deleteNotificationChannel(OLD_CHANNEL_ID)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Alarm snooze (watch)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows while a watch alarm is snoozing; stops the snooze"
                setSound(null, null) // the watch is the alarm; the phone only shows the banner
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        val ids = snoozing.map { it.first.id }.toSet()
        (shown.keys - ids).forEach { nm.cancel(notificationId(it)) }
        shown.keys.retainAll(ids)
        snoozing.forEach { (a, next) ->
            val title = AppTimeFormat.hm(a.hour, a.minute) + (if (a.label.isEmpty()) "" else " · ${a.label}") +
                " · next " + AppTimeFormat.hm(next.hour, next.minute)
            if (shown[a.id] == title) return@forEach
            val stop = PendingIntent.getBroadcast(
                context, notificationId(a.id),
                Intent(context, SnoozeStopReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, a.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText("Rings every ${a.snoozeMin} min until you stop it.")
                .setPriority(NotificationCompat.PRIORITY_HIGH) // heads-up on older Android versions
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true) // the snooze has no end: keep the Stop button at hand
                .addAction(0, "Stop snooze", stop)
                .build()
            runCatching { nm.notify(notificationId(a.id), n) }.onFailure { Timber.w(it, "snooze notification") }
            shown[a.id] = title
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
