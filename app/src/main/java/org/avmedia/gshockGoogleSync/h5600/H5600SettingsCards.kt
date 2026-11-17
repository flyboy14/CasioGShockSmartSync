package org.avmedia.gshockGoogleSync.h5600

import AppSwitch
import AppText
import AppTextLarge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.avmedia.gshockGoogleSync.ui.common.AppCard
import org.avmedia.gshockGoogleSync.ui.common.AppChoiceButton
import org.avmedia.gshockGoogleSync.ui.common.AppSmallButton
import org.avmedia.gshockGoogleSync.ui.common.LoadingSpinner
import org.avmedia.gshockGoogleSync.utils.AppTimeFormat

/**
 * DW-H5600 settings decoded from the CASIO WATCHES app (2026-11-17): heart rate, step goal,
 * night time. Each change is written right away (quick +/- taps are batched).
 */
@Composable
fun H5600SettingsCards(vm: H5600ViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    if (!s.supported) return
    LaunchedEffect(Unit) { vm.refreshSettings() }
    NightTimeCard(vm)
    WatchModesCard(vm)
}

/**
 * Watch modes (0x52): the order the mode button cycles through, and which modes are shown.
 * Hold a mode and drag it to reorder (like the alarms and world clocks). Time & date always
 * stays first. Hidden modes stay listed below (switched off) to bring back.
 */
@Composable
private fun WatchModesCard(vm: H5600ViewModel) {
    val s by vm.state.collectAsState()
    var dragIndex by remember { mutableStateOf(-1) }
    var dragY by remember { mutableStateOf(0f) }
    val heights = remember { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    SettingsCard("Watch modes") {
        val list = s.modeList
        if (list == null) { LoadingSpinner(); return@SettingsCard }
        AppText(text = "Hold and drag to reorder. Switch off the modes you do not use.", fontSize = 13.sp)
        val label = { code: Int -> org.avmedia.gshockapi.model.WatchMode.fromCode(code)?.label ?: "Mode 0x%02X".format(code) }
        val movable = list.filter { it != H5600ViewModel.TIME_AND_DATE }
        val hidden = s.knownModes.filter { it !in list }

        ModeRow(label(H5600ViewModel.TIME_AND_DATE), null) {}

        val target = if (dragIndex < 0) -1 else dropIndex(dragIndex, dragY, movable.indices.map { heights[it] ?: 0 }, 0f)
        LaunchedEffect(target) {
            if (dragIndex >= 0) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        }
        val draggedSize = if (dragIndex < 0) 0f else (heights[dragIndex] ?: 0).toFloat()
        movable.forEachIndexed { i, code -> androidx.compose.runtime.key(code) {
            val dragging = dragIndex == i
            val makeRoom = when {
                dragIndex < 0 || dragging -> 0f
                dragIndex < target && i in (dragIndex + 1)..target -> -draggedSize
                dragIndex > target && i in target until dragIndex -> draggedSize
                else -> 0f
            }
            val shift by androidx.compose.animation.core.animateFloatAsState(makeRoom, label = "modeRoom")
            ModeRow(
                label(code), true,
                modifier = Modifier
                    .onGloballyPositioned { heights[i] = it.size.height }
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) dragY else shift
                        scaleX = if (dragging) 1.03f else 1f
                        scaleY = if (dragging) 1.03f else 1f
                        alpha = if (dragging) 0.85f else 1f
                    }
                    .pointerInput(i, movable.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragIndex = i; dragY = 0f
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, amount -> change.consume(); dragY += amount.y },
                            onDragEnd = {
                                val to = dropIndex(i, dragY, movable.indices.map { heights[it] ?: 0 }, 0f)
                                dragIndex = -1; dragY = 0f
                                if (to != i) vm.setModeList(movable.toMutableList().apply { add(to, removeAt(i)) })
                            },
                            onDragCancel = { dragIndex = -1; dragY = 0f },
                        )
                    },
            ) { vm.setModeList(movable - code) }
        } }
        hidden.forEach { code -> ModeRow(label(code), false) { vm.setModeList(movable + code) } }
    }
}

/** One mode: name and a show/hide switch ([shown] null = fixed, no switch). */
@Composable
private fun ModeRow(name: String, shown: Boolean?, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(text = name, modifier = Modifier.weight(1f))
        if (shown == null) AppText(text = "always first", fontSize = 13.sp)
        else AppSwitch(checked = shown, onCheckedChange = { onToggle() })
    }
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

/** Max / resting heart rate (0x4F). (The DW-H5600 ignores the 0x41 zone-alert bits: tested 2026-11-17.) */
@Composable
private fun HeartRateExtrasCard(vm: H5600ViewModel) {
    val s by vm.state.collectAsState()
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
        s.basic?.let { b ->
            SwitchRow("Step reminder (when you have not moved for a while)", b.stepReminder) { vm.setStepReminder(it) }
        }
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
        picking?.let { start -> androidx.compose.ui.window.Dialog(onDismissRequest = { picking = null }) {
          androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                .padding(16.dp),
          ) {
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
        } }
    }
}
