package org.avmedia.gshockGoogleSync.h5600

import AppSwitch
import AppText
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.avmedia.gshockGoogleSync.h5600.AlarmPool.PhoneAlarm
import org.avmedia.gshockGoogleSync.ui.common.AppChoiceButton
import org.avmedia.gshockGoogleSync.ui.common.AppSmallButton
import org.avmedia.gshockGoogleSync.utils.AppTimeFormat
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
private val SMART_WINDOWS = listOf(15, 30, 45, 60)
private const val DEFAULT_SMART_WINDOW = 30

/** "Once · Tue 18 Nov", "Mon–Fri", … */
fun repeatLabel(a: PhoneAlarm): String =
    if (a.once) "Once" + (a.onceDate?.takeIf { a.enabled }?.let { " · " + it.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) } ?: "")
    else AlarmDayRules.label(a.days)

/** One line summing up an alarm's options, for the alarm card. */
fun optionsSummary(a: PhoneAlarm): String = listOfNotNull(
    repeatLabel(a),
    if (a.vibeOnly) "vibe only" else "sound + vibe",
    if (a.snoozes) "snooze ${a.snoozeMin} min" else "no snooze",
    if (a.isSmart) "smart −${a.smartWindowMin} min" else null,
).joinToString(" · ")

/**
 * Create / edit an alarm with all its options at once; [onSave] gets the result (one watch update
 * instead of one per option).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmEditorDialog(initial: PhoneAlarm, isNew: Boolean, onSave: (PhoneAlarm) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var a by remember { mutableStateOf(initial) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New alarm" else "Edit alarm") },
        confirmButton = { TextButton(onClick = { onSave(a) }) { Text(if (isNew) "Add" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AppText(
                    text = AppTimeFormat.hm(a.hour, a.minute),
                    fontSize = 44.sp,
                    modifier = Modifier.clickable {
                        TimePickerDialog(context, { _, h, m -> a = a.copy(hour = h, minute = m) }, a.hour, a.minute, AppTimeFormat.current()).show()
                    },
                )
                AppText(text = "Tap the time to change it", fontSize = 12.sp)

                OutlinedTextField(
                    value = a.label,
                    onValueChange = { a = a.copy(label = it.take(40)) },
                    label = { Text("Label (phone only)") },
                    placeholder = { Text("e.g. Feed the cats") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                Section("Repeat")
                Choices {
                    val custom = !a.once && a.days != AlarmDayRules.ALL_DAYS && a.days != WEEKDAYS && a.days != WEEKEND
                    AppChoiceButton("Once", a.once, { a = a.copy(once = true) })
                    AppChoiceButton("Every day", !a.once && a.days == AlarmDayRules.ALL_DAYS, { a = a.copy(once = false, days = AlarmDayRules.ALL_DAYS) })
                    AppChoiceButton("Mon–Fri", !a.once && a.days == WEEKDAYS, { a = a.copy(once = false, days = WEEKDAYS) })
                    AppChoiceButton("Weekends", !a.once && a.days == WEEKEND, { a = a.copy(once = false, days = WEEKEND) })
                    AppChoiceButton(if (custom) AlarmDayRules.label(a.days) else "Days…", custom, {
                        pickDays(context, a.days) { d -> a = a.copy(once = false, days = d) }
                    })
                }
                if (a.once) AppText("Rings once at the next %s, then switches itself off.".format(AppTimeFormat.hm(a.hour, a.minute)), fontSize = 12.sp)
                else if (a.days != AlarmDayRules.ALL_DAYS && a.days != WEEKDAYS && a.days != WEEKEND)
                    AppSmallButton(text = "Change days", onClick = { pickDays(context, a.days) { d -> a = a.copy(days = d) } })

                Section("Sound")
                Choices {
                    AppChoiceButton("Sound + vibe", !a.vibeOnly, { a = a.copy(vibeOnly = false) })
                    AppChoiceButton("Vibe only", a.vibeOnly, { a = a.copy(vibeOnly = true) })
                }

                // Off by default; switching on shows a slider for the interval / window.
                ToggleSlider(
                    label = "Snooze",
                    value = a.snoozeMin,
                    choices = AlarmPool.SNOOZE_CHOICES,
                    defaultOn = AlarmPool.DEFAULT_SNOOZE_MIN,
                    valueText = { "every $it min" },
                    onChange = { a = a.copy(snoozeMin = it) },
                )
                if (a.snoozes) AppText(
                    "Rings again every ${a.snoozeMin} min until you stop it in the app or the notification" +
                        (if (a.isSmart) " (from %s on).".format(AppTimeFormat.hm(a.hour, a.minute)) else "."),
                    fontSize = 12.sp,
                )

                ToggleSlider(
                    label = "Smart wake",
                    value = a.smartWindowMin,
                    choices = SMART_WINDOWS,
                    defaultOn = DEFAULT_SMART_WINDOW,
                    valueText = { "up to $it min early" },
                    onChange = { a = a.copy(smartWindowMin = it) },
                )
                if (a.isSmart) AppText("Wakes you up to ${a.smartWindowMin} min early, in light sleep.", fontSize = 12.sp)
            }
        },
    )
}

/**
 * A switch (on when [value] > 0); while on, a slider over [choices] (minutes). Switching on picks the
 * value used last in this dialog, else [defaultOn]; off sets 0. A value not in [choices] snaps to
 * the nearest one.
 */
@Composable
private fun ToggleSlider(
    label: String,
    value: Int,
    choices: List<Int>,
    defaultOn: Int,
    valueText: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    var lastOn by remember { mutableStateOf(if (value > 0) value else defaultOn) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(if (value > 0) "$label · ${valueText(value)}" else label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        AppSwitch(checked = value > 0, onCheckedChange = { on -> onChange(if (on) lastOn else 0) })
    }
    if (value > 0) {
        val index = choices.indices.minBy { kotlin.math.abs(choices[it] - value) }
        androidx.compose.material3.Slider(
            value = index.toFloat(),
            onValueChange = { v -> choices[Math.round(v).coerceIn(choices.indices)].let { lastOn = it; onChange(it) } },
            valueRange = 0f..choices.lastIndex.toFloat(),
            steps = choices.size - 2,
        )
    }
}

@Composable
private fun Section(title: String) = AppText(title, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }

private fun pickDays(context: Context, current: Set<DayOfWeek>, onPicked: (Set<DayOfWeek>) -> Unit) {
    val days = DayOfWeek.entries // Mon..Sun
    val checked = BooleanArray(days.size) { days[it] in current }
    AlertDialog.Builder(context)
        .setTitle("Rings on")
        .setMultiChoiceItems(days.map { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH) }.toTypedArray(), checked) { _, d, on -> checked[d] = on }
        .setPositiveButton("OK") { _, _ -> onPicked(days.filterIndexed { d, _ -> checked[d] }.toSet().ifEmpty { AlarmDayRules.ALL_DAYS }) }
        .setNegativeButton("Cancel", null)
        .show()
}
