package org.avmedia.gshockGoogleSync

sealed class Screens(val route: String) {
    data object Time : Screens("Time")
    data object Health : Screens("Health")
    data object Alarms : Screens("Alarms")
    data object Events : Screens("Events")
    data object Actions : Screens("Actions")
    data object Custom : Screens("Custom") // DW-H5600 additions (fork)
    data object Sport : Screens("Sport") // DW-H5600 heart rate / fitness settings (fork)
    data object Settings : Screens("Settings")
}