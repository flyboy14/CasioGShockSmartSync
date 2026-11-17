package org.avmedia.gshockGoogleSync.h5600

import AppSwitch
import AppText
import AppTextLarge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.avmedia.gshockGoogleSync.ui.common.AppCard
import org.avmedia.gshockGoogleSync.ui.common.AppChoiceButton
import org.avmedia.gshockGoogleSync.ui.common.AppSmallButton
import org.avmedia.gshockGoogleSync.ui.common.LoadingSpinner
import org.avmedia.gshockGoogleSync.utils.AppTimeFormat
import org.avmedia.gshockapi.model.DateOrder
import org.avmedia.gshockapi.model.WeekLanguage

/**
 * DW-H5600 settings decoded from the CASIO WATCHES app (2026-11-17): heart rate, step goal,
 * night time, display. Each change is written right away (quick +/- taps are batched).
 */
@Composable
fun H5600SettingsCards(vm: H5600ViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    if (!s.supported) return
    LaunchedEffect(Unit) { vm.refreshSettings() }
    NightTimeCard(vm)
    DisplayCard(vm)
}

/** "Sport" tab: heart-rate monitor, zone alert, heart-rate values, step goal. Pull down to re-read. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SportScreen(vm: H5600ViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refreshSettings() }
    org.avmedia.gshockGoogleSync.theme.GShockSmartSyncTheme {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                org.avmedia.gshockGoogleSync.ui.common.ScreenTitle("Sport", Modifier)
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = s.busy,
                    onRefresh = { vm.refreshSettings() },
                    modifier = Modifier.weight(1f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    ) {
                        HeartRateMonitorCard(vm)
                        HeartRateExtrasCard(vm)
                        StepGoalCard(vm)
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            AppTextLarge(text = title)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(text = label, modifier = Modifier.weight(1f))
        AppSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StepperRow(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(text = label, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppSmallButton(text = "−", onClick = onMinus)
            AppSmallButton(text = "+", onClick = onPlus)
        }
    }
}

@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T?, perRow: Int, onSelect: (T) -> Unit) {
    options.chunked(perRow).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            row.forEach { (v, label) -> AppChoiceButton(text = label, selected = v == selected, onClick = { onSelect(v) }) }
        }
    }
}

/** HR zone alert (0x41) and max / resting heart rate (0x4F). */
@Composable
private fun HeartRateExtrasCard(vm: H5600ViewModel) {
    val s by vm.state.collectAsState()
    SettingsCard("Heart-rate zone alert") {
        val z = s.zoneAlert
        val p = s.physical
        if (z == null) { LoadingSpinner(); return@SettingsCard }
        AppText(
            text = "During activities the watch alerts you when your heart rate leaves the chosen zone.",
            fontSize = 13.sp,
        )
        SwitchRow("Zone alert", z.enabled) { vm.setZoneAlert(z.copy(enabled = it)) }
        if (z.enabled) {
            // Zones as % of max heart rate (standard 5-zone model); bpm shown from the watch's max HR.
            val pct = listOf(50 to 60, 60 to 70, 70 to 80, 80 to 90, 90 to 100)
            Choices((1..5).map { it to "Zone $it" }, z.zone, 3) { vm.setZoneAlert(z.copy(zone = it)) }
            p?.let {
                val (lo, hi) = pct[z.zone - 1]
                AppText(text = "≈ ${it.maxHr * lo / 100}–${it.maxHr * hi / 100} bpm ($lo–$hi % of max HR)", fontSize = 13.sp)
            }
            SwitchRow("Also notify when back in the zone", z.notifyInRange) { vm.setZoneAlert(z.copy(notifyInRange = it)) }
        }
    }
    SettingsCard("Heart-rate values") {
        val p = s.physical
        if (p == null) { LoadingSpinner(); return@SettingsCard }
        AppText(text = "Used for zones, calories and recovery. Auto = the watch's own estimate.", fontSize = 13.sp)
        SwitchRow("Max heart rate: ${p.maxHr} bpm" + if (p.maxHrManual) "" else " (auto)", p.maxHrManual) {
            vm.setPhysical(p.copy(maxHrManual = it))
        }
        if (p.maxHrManual) StepperRow("Max heart rate", {
            vm.setPhysical(p.copy(maxHr = (p.maxHr - 1).coerceAtLeast(100)))
        }, { vm.setPhysical(p.copy(maxHr = (p.maxHr + 1).coerceAtMost(230))) })
        SwitchRow("Resting heart rate: ${p.restingHr} bpm" + if (p.restingHrManual) "" else " (auto)", p.restingHrManual) {
            vm.setPhysical(p.copy(restingHrManual = it))
        }
        if (p.restingHrManual) StepperRow("Resting heart rate", {
            vm.setPhysical(p.copy(restingHr = (p.restingHr - 1).coerceAtLeast(30)))
        }, { vm.setPhysical(p.copy(restingHr = (p.restingHr + 1).coerceAtMost(120))) })
        AppText(text = "VO2max (measured by the watch): ${p.vo2Max}", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Daily step goal and "goal reached" notification (0x43). */
@Composable
private fun StepGoalCard(vm: H5600ViewModel) {
    val s by vm.state.collectAsState()
    SettingsCard("Daily step goal") {
        val g = s.stepGoal
        if (g == null) { LoadingSpinner(); return@SettingsCard }
        StepperRow("%,d steps".format(g.steps), {
            vm.setStepGoal(g.copy(steps = (g.steps - 500).coerceAtLeast(500)))
        }, { vm.setStepGoal(g.copy(steps = (g.steps + 500).coerceAtMost(99_500))) })
        SwitchRow("Notify when the goal is reached", g.notifyWhenReached) { vm.setStepGoal(g.copy(notifyWhenReached = it)) }
    }
}

/** Night time (0x13 bytes 13-16 and the 0x4F sleep time; the official app keeps them equal). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NightTimeCard(vm: H5600ViewModel) {
    val s by vm.state.collectAsState()
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    SettingsCard("Night time") {
        val b = s.basic
        if (b == null) { LoadingSpinner(); return@SettingsCard }
        AppText(
            text = "Your usual sleep time. The watch uses it for sleep tracking and night-time heart rate, " +
                "and to keep notifications quiet at night.",
            fontSize = 13.sp,
        )
        val fmt = { h: Int, m: Int -> AppTimeFormat.format(java.time.LocalTime.of(h, m)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppSmallButton(text = "From ${fmt(b.nightStartHour, b.nightStartMin)}", onClick = { picking = true })
            AppSmallButton(text = "to ${fmt(b.nightEndHour, b.nightEndMin)}", onClick = { picking = false })
        }
        s.physical?.let { p ->
            if (p.sleepStartHour != b.nightStartHour || p.sleepStartMin != b.nightStartMin ||
                p.wakeHour != b.nightEndHour || p.wakeMin != b.nightEndMin
            ) AppText(text = "Sleep time on the watch differs: ${fmt(p.sleepStartHour, p.sleepStartMin)}–${fmt(p.wakeHour, p.wakeMin)} (saving sets both)", fontSize = 12.sp)
        }
        picking?.let { start ->
            org.avmedia.gshockGoogleSync.ui.common.AppTimePicker(
                onConfirm = { t ->
                    picking = null
                    if (start) vm.setNightTime(t.hour, t.minute, b.nightEndHour, b.nightEndMin)
                    else vm.setNightTime(b.nightStartHour, b.nightStartMin, t.hour, t.minute)
                },
                onDismiss = { picking = null },
                initialHour = if (start) b.nightStartHour else b.nightEndHour,
                initialMinute = if (start) b.nightStartMin else b.nightEndMin,
            )
        }
    }
}

/** Date order, weekday language and font (0x13). */
@Composable
private fun DisplayCard(vm: H5600ViewModel) {
    val s by vm.state.collectAsState()
    SettingsCard("Display") {
        val b = s.basic
        if (b == null) { LoadingSpinner(); return@SettingsCard }
        AppText(text = "Date order", modifier = Modifier.padding(top = 4.dp))
        Choices(DateOrder.entries.map { it.code to it.label }, b.dateOrder, 2) { vm.setBasic(b.copy(dateOrder = it)) }
        AppText(text = "Weekday language", modifier = Modifier.padding(top = 8.dp))
        Choices(WeekLanguage.entries.map { it.code to it.label }, b.weekLanguage, 3) { vm.setBasic(b.copy(weekLanguage = it)) }
        AppText(text = "Digit font", modifier = Modifier.padding(top = 8.dp))
        Choices(listOf(false to "Squad", true to "7-segment"), b.sevenSegmentFont, 2) { vm.setBasic(b.copy(sevenSegmentFont = it)) }
    }
}
