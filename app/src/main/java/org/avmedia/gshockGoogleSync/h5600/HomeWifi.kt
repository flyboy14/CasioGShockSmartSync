package org.avmedia.gshockGoogleSync.h5600

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * "Sun at home": while the phone is on the user's home Wi-Fi, the Auto face shows Sun data all day
 * (walking from room to room is not worth counting). Away from it, Auto keeps its steps / sunset logic.
 *
 * At home = connected to one of the saved network names (SSIDs; a home often has several: 2.4 GHz,
 * 5 GHz, an extender). Android only reveals the name with precise
 * location allowed, and in the background only with "Allow all the time"; without it the name reads
 * as unknown and the app never thinks it is home.
 */
object HomeWifi {
    private const val KEY_ENABLED = "home_sun"
    private const val KEY_SSID = "home_ssid" // old single name, read once into the list
    private const val KEY_SSIDS = "home_ssids" // JSON array
    private const val UNKNOWN = "<unknown ssid>"

    fun isEnabled(context: Context): Boolean = H5600Prefs.get(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, on: Boolean) = H5600Prefs.get(context).edit().putBoolean(KEY_ENABLED, on).apply()

    fun homeSsids(context: Context): List<String> {
        val p = H5600Prefs.get(context)
        p.getString(KEY_SSIDS, null)?.let { j ->
            return runCatching { org.json.JSONArray(j).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        }
        return listOfNotNull(p.getString(KEY_SSID, null))
    }

    fun setHomeSsids(context: Context, list: List<String>) =
        H5600Prefs.get(context).edit().putString(KEY_SSIDS, org.json.JSONArray(list.distinct()).toString()).remove(KEY_SSID).apply()

    fun addHomeSsid(context: Context, ssid: String) = setHomeSsids(context, homeSsids(context) + ssid)
    fun removeHomeSsid(context: Context, ssid: String) = setHomeSsids(context, homeSsids(context) - ssid)

    /**
     * Networks the phone can see now (latest Wi-Fi scan), strongest first, the current one first.
     * Android does not let apps read the saved-networks list; nearby networks are the next best.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun nearby(context: Context): List<String> {
        val wm = context.getSystemService(WifiManager::class.java) ?: return listOfNotNull(_current.value)
        @Suppress("DEPRECATION") runCatching { wm.startScan() } // throttled by Android; the last results are used anyway
        val seen = runCatching { wm.scanResults }.getOrNull().orEmpty()
            .sortedByDescending { it.level }
            .mapNotNull { r ->
                clean(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) r.wifiSsid?.toString() else @Suppress("DEPRECATION") r.SSID)
            }
        return (listOfNotNull(_current.value) + seen).distinct()
    }

    private val _current = MutableStateFlow<String?>(null)

    /** Name of the Wi-Fi the phone is on now; null when not on Wi-Fi or the name is hidden. */
    val currentSsid: StateFlow<String?> = _current

    /** Pure: home when the feature is on and the current network is one of the saved ones. */
    fun atHome(enabled: Boolean, home: Collection<String>, current: String?): Boolean =
        enabled && current != null && current in home

    fun atHome(context: Context): Boolean = atHome(isEnabled(context), homeSsids(context), _current.value)

    /** Precise location in the foreground and, on Android 10+, in the background ("Allow all the time"). */
    fun hasPermission(context: Context): Boolean {
        val granted = { p: String -> ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED }
        return granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Follows the Wi-Fi connection for the life of the process; [onChange] runs when the network
     * name changes (connect, disconnect, roaming to another network). Safe to call again (e.g.
     * after the permission was granted): the old callback is replaced.
     */
    fun start(context: Context, onChange: () -> Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        val set = { ssid: String? ->
            if (_current.value != ssid) {
                _current.value = ssid
                Timber.i("H5600 Wi-Fi: ${ssid ?: "none"}")
                onChange()
            }
        }
        val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    set(clean((caps.transportInfo as? WifiInfo)?.ssid))
                override fun onLost(network: Network) = set(null)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                @Suppress("DEPRECATION")
                override fun onAvailable(network: Network) =
                    set(clean(context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid))
                override fun onLost(network: Network) = set(null)
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { callback = cb }
            .onFailure { Timber.w(it, "H5600 Wi-Fi callback") }
    }

    /** "\"MyWifi\"" → "MyWifi"; the hidden-name placeholder → null. */
    fun clean(ssid: String?): String? = ssid?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it != UNKNOWN }
}
