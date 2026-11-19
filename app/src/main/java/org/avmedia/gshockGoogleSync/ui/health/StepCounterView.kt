package org.avmedia.gshockGoogleSync.ui.health

import AppText
import AppTextLarge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.avmedia.gshockGoogleSync.R
import org.avmedia.gshockGoogleSync.ui.common.AppCard
import java.util.Calendar
import java.util.Locale

@Composable
fun StepCounterView(
    modifier: Modifier = Modifier,
    healthViewModel: HealthViewModel = hiltViewModel()
) {
    val state by healthViewModel.state.collectAsState()

    AppCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Compact: Today, Hourly, Daily and Monthly fit on one screen.
            // ── TODAY section ──────────────────────────────────────────
            SectionLabel(text = stringResource(id = R.string.today))
            Spacer(modifier = Modifier.height(2.dp))

            val currentSteps = state.stepCounterData.currentDaySteps ?: 0
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                StepsProgressRing(
                    modifier = Modifier.size(120.dp),
                    currentSteps = currentSteps,
                    goal = state.stepGoal,
                    distanceKm = state.distanceKm,
                    calories = state.calories
                )
            }

            SectionGap()

            // ── HOURLY section ─────────────────────────────────────────
            // All 24 hours (midnight to now; later hours empty), scrollable, opened at the current hour.
            val hourlyKnown = state.stepCounterData.hourlySteps
            if (hourlyKnown.isEmpty()) {
                SectionLabel(text = stringResource(id = R.string.hourly))
                EmptyHistory(Modifier.weight(1f))
            } else {
                val hourly: List<Int?> = (0 until 24).map { h -> hourlyKnown.getOrNull(h) }
                val scroll = androidx.compose.foundation.rememberScrollState()
                val density = androidx.compose.ui.platform.LocalDensity.current
                val nowHour = (hourlyKnown.size - 1).coerceIn(0, 23)
                var hourSel by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
                val hour = hourSel ?: nowHour
                SectionHeader(stringResource(id = R.string.hourly), hourLabel(hour), hourly[hour], state.weight)
                Spacer(modifier = Modifier.height(2.dp))
                // Keyed on maxValue too: before the row is measured maxValue is 0 and scrollTo clamps to 0.
                androidx.compose.runtime.LaunchedEffect(nowHour, scroll.maxValue) {
                    // Current hour at the right edge, ~8 hours visible before it.
                    val step = with(density) { (HOURLY_BAR + HOURLY_GAP).toPx() }
                    scroll.scrollTo(((nowHour - 7) * step).toInt().coerceAtLeast(0))
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f).horizontalScroll(scroll)) {
                    StepsBarChart(
                        modifier = Modifier.fillMaxSize(),
                        values = hourly,
                        labels = (0 until 24).map { hourLabel(it) },
                        barWidth = HOURLY_BAR,
                        nullText = "",
                        selected = hour,
                        onSelect = { hourSel = it },
                    )
                }
            }

            SectionGap()

            // ── DAILY section ──────────────────────────────────────────
            // This month (the 1st to today), scrollable like Hourly, opened at today (right edge).
            // Past days the phone has no data for are shown as "–" (not 0).
            val pastDaysThisMonth = java.time.LocalDate.now().dayOfMonth - 1
            val daily: List<Int?> = state.stepCounterData.dailyHistory.takeLast(pastDaysThisMonth) +
                (state.stepCounterData.currentDaySteps ?: 0)
            val dayLabels = dailyLabels(daily.size)
            var daySel by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
            val day = (daySel ?: daily.lastIndex).coerceIn(0, daily.lastIndex)
            SectionHeader(stringResource(id = R.string.daily), dayLabels[day].replace('\n', ' '), daily[day], state.weight)
            Spacer(modifier = Modifier.height(2.dp))
            run {
                val dayScroll = androidx.compose.foundation.rememberScrollState()
                // Keyed on maxValue: before the row is measured it is 0 and scrollTo clamps to 0.
                androidx.compose.runtime.LaunchedEffect(daily.size, dayScroll.maxValue) { dayScroll.scrollTo(dayScroll.maxValue) }
                Box(modifier = Modifier.fillMaxWidth().weight(1f).horizontalScroll(dayScroll)) {
                    StepsBarChart(
                        modifier = Modifier.fillMaxSize(),
                        values = daily,
                        labels = dayLabels,
                        barWidth = HOURLY_BAR,
                        selected = day,
                        onSelect = { daySel = it },
                    )
                }
            }

            if (state.monthly.isNotEmpty()) {
                SectionGap()
                val months = state.monthly.map { it.second }
                val monthLabels = state.monthly.map { (m, _) ->
                    m.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                }
                var monthSel by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
                val month = (monthSel ?: months.lastIndex).coerceIn(0, months.lastIndex)
                SectionHeader("Monthly", monthLabels[month], months[month], state.weight)
                StepsBarChart(
                    modifier = Modifier.weight(1f),
                    values = months,
                    labels = monthLabels,
                    compactNumbers = true,
                    selected = month,
                    onSelect = { monthSel = it },
                )
            }
        }
    }
}

/**
 * Section title with the selected bar's distance and calories on the right ("Tu 18 · 5.7 km ·
 * 410 kcal"); the steps are on the bar itself. Estimated from steps, like the Today ring.
 */
@Composable
private fun SectionHeader(title: String, what: String, steps: Int?, weightKg: Int) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = what + " · " + (steps?.let {
                String.format(Locale.US, "%.1f km · %.0f kcal", stepsKm(it), stepsKcal(it, weightKg))
            } ?: "no data"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 0.5.dp
    )
}

@Composable
private fun StepsProgressRing(
    currentSteps: Int,
    goal: Int,
    distanceKm: Float,
    calories: Float,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progress = (currentSteps / goal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 10.dp.toPx()
            val diameter = size.minDimension
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            drawArc(
                color = trackColor,
                startAngle = -180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
            drawArc(
                color = primaryColor,
                startAngle = -180f,
                sweepAngle = 180f * progress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            AppText(text = currentSteps.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "of $goal steps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = String.format(Locale.US, "%.1f km", distanceKm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = String.format(Locale.US, "%.0f kcal", calories),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private val HOURLY_BAR = 32.dp
private val CHART_HEIGHT = 100.dp

/** Divider between sections, kept small so all four fit on one screen. */
@Composable
private fun SectionGap() {
    Spacer(modifier = Modifier.height(6.dp))
    SectionDivider()
    Spacer(modifier = Modifier.height(6.dp))
}
private val HOURLY_GAP = 8.dp

/** Bar chart; null = no data (no bar, [nullText] above). [barWidth] set = fixed width (for scrolling). */
@Composable
private fun StepsBarChart(
    values: List<Int?>,
    labels: List<String>,
    modifier: Modifier = Modifier.height(CHART_HEIGHT), // callers pass weight(1f) to fill the screen
    barWidth: androidx.compose.ui.unit.Dp? = null,
    nullText: String = "–",
    compactNumbers: Boolean = false, // 12345 -> "12.3k"
    /** Highlighted bar (the others are dimmed); tapping a bar calls [onSelect] with its index. */
    selected: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val maxValue = (values.filterNotNull().maxOrNull() ?: 1).coerceAtLeast(1)
    val sized = if (barWidth != null) modifier.width((barWidth + HOURLY_GAP) * values.size + 16.dp) else modifier.fillMaxWidth()

    val fixedBar = barWidth
    Canvas(
        modifier = sized
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .then(
                if (onSelect == null) Modifier
                else Modifier.pointerInput(values.size, fixedBar) {
                    detectTapGestures { o ->
                        val spacing = 8.dp.toPx()
                        val bw = fixedBar?.toPx() ?: ((size.width - spacing * (values.size - 1)) / values.size)
                        val i = (o.x / (bw + spacing)).toInt()
                        if (i in values.indices) onSelect(i)
                    }
                }
            )
    ) {
        val spacing = 8.dp.toPx()
        val totalSpacing = spacing * (values.size - 1)
        val barWidth = barWidth?.toPx() ?: ((size.width - totalSpacing) / values.size)
        val topLabelSpace = 16.dp.toPx()
        val labelLines = labels.maxOfOrNull { it.count { c -> c == '\n' } + 1 } ?: 1 // "Mo\n17": two lines
        val bottomLabelSpace = 18.dp.toPx() + (labelLines - 1) * 12.sp.toPx()
        val chartHeight = size.height - topLabelSpace - bottomLabelSpace

        val paint = android.graphics.Paint().apply {
            color = textColor
            textSize = 10.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }

        values.forEachIndexed { index, value ->
            val barHeight = ((value ?: 0).toFloat() / maxValue) * chartHeight
            val left = index * (barWidth + spacing)
            val top = topLabelSpace + (chartHeight - barHeight)

            if (value != null) drawRoundRect(
                color = if (selected == null || selected == index) barColor else barColor.copy(alpha = 0.45f),
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight.coerceAtLeast(4.dp.toPx())),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )

            drawIntoCanvas { canvas ->
                val text = when {
                    value == null -> nullText
                    compactNumbers && value >= 1000 -> String.format(Locale.US, "%.1fk", value / 1000f)
                    else -> value.toString()
                }
                canvas.nativeCanvas.drawText(text, left + barWidth / 2f, top - 4.dp.toPx(), paint)
                val lines = labels[index].split('\n')
                lines.forEachIndexed { i, line ->
                    val y = size.height - 2.dp.toPx() - (lines.size - 1 - i) * 12.sp.toPx()
                    canvas.nativeCanvas.drawText(line, left + barWidth / 2f, y, paint)
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier.height(CHART_HEIGHT)) {
    Box(
        modifier = modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text("No history", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "12a", "1a" … "11p" (or "0" … "23" in 24-hour mode). */
private fun hourLabel(hour: Int): String =
    if (org.avmedia.gshockGoogleSync.utils.AppTimeFormat.current()) hour.toString()
    else (if (hour % 12 == 0) 12 else hour % 12).toString() + (if (hour < 12) "a" else "p")

private fun dailyLabels(count: Int): List<String> {
    val calendar = Calendar.getInstance()
    val dayNames = mapOf(
        Calendar.SUNDAY to "Su",
        Calendar.MONDAY to "Mo",
        Calendar.TUESDAY to "Tu",
        Calendar.WEDNESDAY to "We",
        Calendar.THURSDAY to "Th",
        Calendar.FRIDAY to "Fr",
        Calendar.SATURDAY to "Sa"
    )
    return List(count) { index ->
        calendar.timeInMillis = System.currentTimeMillis()
        calendar.add(Calendar.DAY_OF_YEAR, index - (count - 1))
        (dayNames[calendar.get(Calendar.DAY_OF_WEEK)] ?: "") + "\n" + calendar.get(Calendar.DAY_OF_MONTH)
    }
}
