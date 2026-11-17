package org.avmedia.gshockGoogleSync.ui.time

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import org.avmedia.gshockGoogleSync.h5600.H5600ScheduleRunner
import javax.inject.Inject

/** Sends the clock marked "on the watch" to the watch (as its world city) in the background. */
@HiltViewModel
class WorldClocksViewModel @Inject constructor(
    private val runner: H5600ScheduleRunner,
) : ViewModel() {
    fun syncWatchCity() = runner.requestWorldCitySync()
}
