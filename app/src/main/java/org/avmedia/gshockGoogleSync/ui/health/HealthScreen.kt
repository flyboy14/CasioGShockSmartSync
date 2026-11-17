package org.avmedia.gshockGoogleSync.ui.health

import AppText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.avmedia.gshockGoogleSync.R
import org.avmedia.gshockGoogleSync.theme.GShockSmartSyncTheme
import org.avmedia.gshockGoogleSync.ui.common.ScreenTitle

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HealthScreen() {
    val healthViewModel: HealthViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val isRefreshing by healthViewModel.isRefreshing.collectAsState()

    GShockSmartSyncTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                ScreenTitle(
                    stringResource(id = R.string.health),
                    Modifier
                )

                // Swipe down to re-sync steps from the watch.
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { healthViewModel.onAction(HealthAction.PullRefresh) },
                    modifier = Modifier.weight(1f),
                ) {
                // Exactly one screen tall: the charts share whatever height is left. The outer
                // scroll stays (with nothing to scroll) so the pull-to-refresh gesture still works.
                androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val screenHeight = maxHeight
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(screenHeight)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // "Synced at …" (or "Syncing…" while a pull-to-refresh / fetch runs)
                    val healthState by healthViewModel.state.collectAsState()
                    AppText(
                        text = when {
                            isRefreshing -> "Syncing with the watch…"
                            healthState.syncedAt == null -> "Not synced yet — swipe down to sync"
                            else -> healthState.syncedAt!!.let { t ->
                                val time = org.avmedia.gshockGoogleSync.utils.AppTimeFormat.format(t.toLocalTime())
                                if (t.toLocalDate() == java.time.LocalDate.now()) "Synced at $time"
                                else "Synced " + t.format(java.time.format.DateTimeFormatter.ofPattern("d MMM")) + " at $time"
                            }
                        },
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )

                    // Steps card — shows Today, Hourly, and Daily panels all at once
                    StepCounterView(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )

                    // Profile button removed: the DW-H5600 does not accept the exercise profile
                    // (0x2D rejected, 0x45 writes dropped; tested 2026-11-17).
                }
                }
                }
                }
            }
        }
    }

}

@Preview(showBackground = true)
@Composable
fun PreviewHealthScreen() {
    HealthScreen()
}
