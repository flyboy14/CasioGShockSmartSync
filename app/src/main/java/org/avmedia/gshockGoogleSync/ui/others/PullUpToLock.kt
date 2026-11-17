package org.avmedia.gshockGoogleSync.ui.others

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * The mirror of pull-to-refresh: on any tab already scrolled to the bottom, keep pulling up and a
 * lock badge rises from the bottom; release past [TRIGGER] to lock the app (shows the cover).
 * Works on the scroll left over after the tab's own scrolling, so normal scrolling is unaffected.
 */
class PullUpToLockState(private val triggerPx: Float) {
    /** How far the user has pulled past the bottom (px, with resistance). */
    var pull by mutableFloatStateOf(0f)
        private set

    val progress: Float get() = (pull / triggerPx).coerceIn(0f, 1.5f)

    // Only a pull that starts with the tab already at the bottom counts: if this gesture scrolled
    // the tab, its rest past the bottom is ignored (a long swipe down a short tab used to lock).
    private var scrolledThisGesture = false
    val armed: Boolean get() = pull >= triggerPx

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Finger moving back down while pulled: shrink the pull first, before the tab scrolls.
            if (pull > 0f && available.y > 0f && source == NestedScrollSource.UserInput) {
                val used = minOf(available.y, pull / RESISTANCE)
                pull = (pull - used * RESISTANCE).coerceAtLeast(0f)
                return Offset(0f, used)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if (consumed.y != 0f) { scrolledThisGesture = true; return Offset.Zero }
            // Upward drag the tab could not use: it is already at the bottom.
            if (available.y < 0f && !scrolledThisGesture) {
                pull += -available.y * RESISTANCE
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity =
            if (pull > 0f) { release(); available } else Velocity.Zero // no fling out of a pull
    }

    /** Finger lifted: lock if pulled far enough. Called from the fling and from [releaseWatcher]. */
    fun release() {
        val lock = armed
        pull = 0f
        if (lock) CoverLock.lock()
    }

    /**
     * Watches for the last finger lifting, without consuming anything: the fling the tab dispatches
     * on release does not always reach this connection (pull-to-refresh tabs), so this is the
     * reliable release signal.
     */
    val releaseWatcher: Modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            scrolledThisGesture = false // a new touch: may start a pull if the tab is at the bottom
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
            if (pull > 0f) release()
        }
    }

    private companion object {
        const val RESISTANCE = 0.5f
    }
}

private val TRIGGER = 90.dp

@Composable
fun rememberPullUpToLockState(): PullUpToLockState {
    val px = with(LocalDensity.current) { TRIGGER.toPx() }
    return remember(px) { PullUpToLockState(px) }
}

/**
 * The lock badge that rises from the bottom while pulling (no text: open lock while pulling,
 * closed lock once releasing would lock); place it over the content, bottom-aligned.
 */
@Composable
fun PullUpToLockIndicator(state: PullUpToLockState, modifier: Modifier = Modifier) {
    if (state.pull <= 0f) return
    val armed = state.armed
    Box(
        modifier = modifier
            .padding(bottom = 16.dp)
            .graphicsLayer {
                val p = state.progress.coerceAtMost(1f)
                alpha = p
                translationY = (1f - p) * 60.dp.toPx() // rises as you pull
                scaleX = if (armed) 1.15f else 1f
                scaleY = if (armed) 1.15f else 1f
            }
            .background(if (armed) Color(0xFF3A3A3A) else Color(0xFF2A2A2A), CircleShape)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (armed) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = if (armed) "Release to lock" else "Pull up to lock",
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}
