package org.avmedia.gshockGoogleSync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.avmedia.gshockGoogleSync.session.ConnectionButton
import org.avmedia.gshockGoogleSync.ui.others.CoverScreen
import org.avmedia.gshockGoogleSync.ui.others.PreConnectionScreen
import org.avmedia.gshockGoogleSync.ui.others.RunActionsScreen
import org.avmedia.gshockGoogleSync.ui.others.RunFindPhoneScreen

@Composable
fun AppNavigationHost(
    contentPadding: PaddingValues,
    viewModel: AppNavigationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val companionDeviceCoordinator = viewModel.companionDeviceCoordinator

    LaunchedEffect(state.screen) {
        if (state.screen == AppScreen.INITIAL) {
            companionDeviceCoordinator.checkPairedDevicesOrNotify()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        val coverLocked by org.avmedia.gshockGoogleSync.ui.others.CoverLock.locked.collectAsState()
        val startPeek by org.avmedia.gshockGoogleSync.ui.others.StartScreenPeek.shown.collectAsState()
        // A disconnect ends the peek: otherwise it could hide the real start screen / a reconnect.
        LaunchedEffect(state.screen) {
            if (state.screen == AppScreen.INITIAL || state.screen == AppScreen.PRE_CONNECTION) {
                org.avmedia.gshockGoogleSync.ui.others.StartScreenPeek.shown.value = false
            }
        }
        if (startPeek) {
            // "Connected" tapped on the cover: the start screen, watch still connected. Back or a tap
            // anywhere returns to the cover (the overlay keeps the list's buttons from acting).
            val back = { org.avmedia.gshockGoogleSync.ui.others.StartScreenPeek.shown.value = false }
            androidx.activity.compose.BackHandler { back() }
            Box(modifier = Modifier.fillMaxSize()) {
                PreConnectionScreen()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { back() },
                )
            }
        } else if (coverLocked) {
            CoverScreen(
                onUnlock = { org.avmedia.gshockGoogleSync.ui.others.CoverLock.unlock(); viewModel.showMainNavigation() },
                isConnected = viewModel.repository.isConnected(),
            )
        } else when (state.screen) {
            AppScreen.INITIAL, AppScreen.PRE_CONNECTION -> PreConnectionScreen()
            AppScreen.CONTENT_SELECTOR -> ContentSelector(viewModel)
            AppScreen.MAIN_NAVIGATION ->
                BottomNavigationBarWithPermissions(repository = viewModel.repository)
            AppScreen.RUN_ACTIONS -> RunActionsScreen()
            AppScreen.ERROR -> PreConnectionScreen()
        }
    }
}

@Composable
private fun ContentSelector(viewModel: AppNavigationViewModel) {
    val repository = viewModel.repository
    val sessionState by viewModel.sessionState.collectAsState()
    when (sessionState.button) {
        ConnectionButton.ALWAYS_CONNECTED -> {
            CoverScreen(
                onUnlock = viewModel::showMainNavigation,
                isConnected = repository.isConnected()
            )
        }

        ConnectionButton.FROM_ACTION, ConnectionButton.AUTOMATIC_SYNC -> RunActionsScreen()
        ConnectionButton.FIND -> RunFindPhoneScreen()
        ConnectionButton.NORMAL, null -> BottomNavigationBarWithPermissions(repository = repository)
    }
}
