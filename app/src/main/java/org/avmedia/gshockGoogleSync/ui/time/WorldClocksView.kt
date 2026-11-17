package org.avmedia.gshockGoogleSync.ui.time

import AppSwitch
import AppText
import AppTextLarge
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Watch
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.avmedia.gshockGoogleSync.ui.common.AppCard
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** A clock on the Time tab; [onWatch] = also the watch's world city (at most one). */
data class WorldClock(val name: String, val zone: String, val onWatch: Boolean = false) {
    val zoneId: ZoneId get() = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC"))
}

/** The saved list of extra clocks, in display order. */
object WorldClocks {
    private const val PREFS = org.avmedia.gshockGoogleSync.h5600.H5600Prefs.NAME
    private const val KEY = "world_clocks"
    private val DEFAULTS = listOf(WorldClock("Minsk", "Europe/Minsk"), WorldClock("Los Angeles", "America/Los_Angeles"))

    fun load(context: Context): List<WorldClock> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return DEFAULTS
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> WorldClock(o.getString("name"), o.getString("zone"), o.optBoolean("onWatch")) } }
        }.getOrDefault(DEFAULTS)
    }

    fun save(context: Context, clocks: List<WorldClock>) {
        val a = JSONArray()
        clocks.forEach { a.put(JSONObject().put("name", it.name).put("zone", it.zone).put("onWatch", it.onWatch)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    /** The clock shown as the watch's world city, if any. */
    fun watchClock(context: Context): WorldClock? = load(context).firstOrNull { it.onWatch }

    /** "Europe/Minsk" -> "Minsk", "America/Argentina/Buenos_Aires" -> "Buenos Aires". */
    fun cityOf(zone: String): String = zone.substringAfterLast('/').replace('_', ' ')

    /** "UTC+3", "UTC−7", "UTC+5:30" right now. */
    fun offsetLabel(zone: String): String {
        val secs = runCatching { ZoneId.of(zone).rules.getOffset(Instant.now()).totalSeconds }.getOrDefault(0)
        if (secs == 0) return "UTC"
        val sign = if (secs > 0) "+" else "−"
        val abs = kotlin.math.abs(secs) / 60
        return "UTC$sign${abs / 60}" + if (abs % 60 != 0) ":%02d".format(abs % 60) else ""
    }

    private val REGIONS = listOf(
        "Africa/", "America/", "Antarctica/", "Arctic/", "Asia/", "Atlantic/", "Australia/", "Europe/", "Indian/", "Pacific/",
    )

    /** City time zones (Region/City), sorted by city name. */
    val allZones: List<String> by lazy {
        ZoneId.getAvailableZoneIds()
            .filter { z -> REGIONS.any { z.startsWith(it) } }
            .filterNot { z -> z.substringAfterLast('/').let { c -> c.length <= 4 && c == c.uppercase() } } // ACT, NSW: old aliases
            .sortedBy { cityOf(it) }
    }
}

@Composable
fun WorldClocksView(modifier: Modifier = Modifier, vm: WorldClocksViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    val context = LocalContext.current
    // Hold-and-drag reordering, like the alarms
    var dragIndex by remember { mutableStateOf(-1) }
    var dragY by remember { mutableStateOf(0f) }
    val heights = remember { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var clocks by remember { mutableStateOf(WorldClocks.load(context)) }
    // null = no editor; an index outside the list = new clock
    var editing by remember { mutableStateOf<Int?>(null) }
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = ZonedDateTime.now()
        }
    }
    // Subscribe to the app-wide format so a 12/24h change recomposes this card immediately.
    val is24h by org.avmedia.gshockGoogleSync.utils.AppTimeFormat.is24h.collectAsState()
    val fmt = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a")

    fun update(list: List<WorldClock>) {
        val watchBefore = clocks.firstOrNull { it.onWatch }
        clocks = list
        WorldClocks.save(context, list)
        val watchAfter = list.firstOrNull { it.onWatch }
        // Only the clock on the watch matters to the watch (its name and zone).
        if (watchAfter != watchBefore && watchAfter != null) vm.syncWatchCity()
    }

    AppCard(modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            val target = if (dragIndex < 0) -1
                else org.avmedia.gshockGoogleSync.h5600.dropIndex(dragIndex, dragY, clocks.indices.map { heights[it] ?: 0 }, 0f)
            LaunchedEffect(target) {
                if (dragIndex >= 0) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            }
            val draggedSize = if (dragIndex < 0) 0f else (heights[dragIndex] ?: 0).toFloat()
            clocks.forEachIndexed { i, clock -> androidx.compose.runtime.key(clock.name, clock.zone, i) {
                val there = now.withZoneSameInstant(clock.zoneId)
                val dayDiff = ChronoUnit.DAYS.between(now.toLocalDate(), there.toLocalDate())
                val dragging = dragIndex == i
                val makeRoom = when {
                    dragIndex < 0 || dragging -> 0f
                    dragIndex < target && i in (dragIndex + 1)..target -> -draggedSize
                    dragIndex > target && i in target until dragIndex -> draggedSize
                    else -> 0f
                }
                val shift by androidx.compose.animation.core.animateFloatAsState(makeRoom, label = "clockRoom")
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .onGloballyPositioned { heights[i] = it.size.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragY else shift
                            scaleX = if (dragging) 1.03f else 1f
                            scaleY = if (dragging) 1.03f else 1f
                            alpha = if (dragging) 0.85f else 1f
                        }
                        .pointerInput(i, clocks.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragIndex = i; dragY = 0f
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, amount -> change.consume(); dragY += amount.y },
                                onDragEnd = {
                                    val to = org.avmedia.gshockGoogleSync.h5600.dropIndex(i, dragY, clocks.indices.map { heights[it] ?: 0 }, 0f)
                                    dragIndex = -1; dragY = 0f
                                    if (to != i) update(clocks.toMutableList().apply { add(to, removeAt(i)) })
                                },
                                onDragCancel = { dragIndex = -1; dragY = 0f },
                            )
                        }
                        .clickable { editing = i }
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppText(text = clock.name)
                            if (clock.onWatch) Icon(
                                Icons.Filled.Watch, contentDescription = "World time on the watch",
                                modifier = Modifier.padding(start = 6.dp).size(16.dp),
                            )
                        }
                        AppText(text = there.format(DateTimeFormatter.ofPattern("EEE, d MMM")), fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dayDiff != 0L) {
                            AppText(text = if (dayDiff > 0) "+1 day  " else "−1 day  ", fontSize = 13.sp)
                        }
                        AppTextLarge(text = there.format(fmt))
                    }
                }
            } }
            // Tiny "+" in the bottom-right corner; tap a clock to edit or delete it.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clip(CircleShape)
                        .clickable { editing = -1 },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add clock", modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    editing?.let { index ->
        val isNew = index !in clocks.indices
        WorldClockEditor(
            initial = if (isNew) null else clocks[index],
            onSave = { c ->
                val others = if (c.onWatch) clocks.map { it.copy(onWatch = false) } else clocks
                update(if (isNew) others + c
                    else others.toMutableList().also { it[index] = c })
                editing = null
            },
            onDelete = if (isNew) null else ({ update(clocks.filterIndexed { i, _ -> i != index }); editing = null }),
            onDismiss = { editing = null },
        )
    }
}

/** Name + time zone (searchable list). [initial] null = new clock. */
@Composable
private fun WorldClockEditor(
    initial: WorldClock?,
    onSave: (WorldClock) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var zone by remember { mutableStateOf(initial?.zone) }
    var query by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var onWatch by remember { mutableStateOf(initial?.onWatch ?: false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New clock" else "Edit clock") },
        confirmButton = {
            TextButton(
                enabled = zone != null,
                onClick = { zone?.let { z -> onSave(WorldClock(name.trim().ifEmpty { WorldClocks.cityOf(z) }, z, onWatch)) } },
            ) { Text(if (initial == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text("Name") },
                    placeholder = { Text(zone?.let { WorldClocks.cityOf(it) } ?: "e.g. Home") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        AppText(text = "World time on the watch")
                        AppText(text = "The watch's world city; replaces the current one.", fontSize = 12.sp)
                    }
                    AppSwitch(checked = onWatch, onCheckedChange = { onWatch = it })
                }
                AppText(
                    text = zone?.let { "Time zone: ${it.replace('_', ' ')} (${WorldClocks.offsetLabel(it)})" } ?: "Pick a time zone:",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search city or region") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                val q = query.trim().replace(' ', '_')
                val matches = remember(q) {
                    if (q.isEmpty()) WorldClocks.allZones
                    else WorldClocks.allZones.filter { it.contains(q, ignoreCase = true) }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 4.dp)) {
                    items(matches, key = { it }) { z ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                // Keep a custom name; replace one that was just the old city.
                                if (name.isBlank() || zone?.let { WorldClocks.cityOf(it) } == name) name = WorldClocks.cityOf(z)
                                zone = z
                            }.padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                AppText(text = WorldClocks.cityOf(z) + if (z == zone) "  ✓" else "")
                                AppText(text = z.substringBeforeLast('/').replace('_', ' '), fontSize = 12.sp)
                            }
                            AppText(text = WorldClocks.offsetLabel(z), fontSize = 13.sp)
                        }
                    }
                }
                if (onDelete != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (confirmDelete) {
                            AppText(text = "Delete this clock?", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { confirmDelete = false }) { Text("No") }
                            TextButton(onClick = onDelete) { Text("Delete") }
                        } else {
                            TextButton(onClick = { confirmDelete = true }) { Text("Delete clock") }
                        }
                    }
                }
            }
        },
    )
}
