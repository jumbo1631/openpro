package com.openpro.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.openpro.camera.CameraSession
import com.openpro.camera.CameraState
import com.openpro.camera.wifi.RouteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

/** The phone's internet connection as OpenPro sees it (through the VPN if one covers OpenPro). */
data class Internet(
    val connected: Boolean = false,
    val validated: Boolean = false,
    val wifi: Boolean = false,
    val mobile: Boolean = false,
    val vpn: Boolean = false,
) {
    val label: String
        get() = when {
            !connected -> "No internet"
            else -> buildString {
                append(if (wifi) "Wi-Fi" else if (mobile) "Mobile data" else "Network")
                if (vpn) append(" + VPN")
                if (!validated && !vpn) append(" (no internet)")
            }
        }

    /**
     * Good enough to upload over. Some VPNs never report validation, so a VPN with a real network
     * underneath counts too.
     */
    fun usable(allowMobile: Boolean) =
        connected && (validated || vpn && (wifi || mobile)) && (allowMobile || !mobile || wifi)
}

enum class CameraWifiState { Off, Joining, Connected }

data class NetStatus(
    val camera: CameraWifiState = CameraWifiState.Off,
    val cameraSsid: String? = null,
    val cameraRoute: RouteMode? = null,
    val internet: Internet = Internet(),
    /** Set while the batch is deliberately moving the phone between networks. */
    val switching: String? = null,
    val dualWifi: Boolean = false,
)

/**
 * Watches which networks OpenPro has: the camera's WiFi (from the camera session) and the default
 * internet route. Also carries the batch's "Switching Wi-Fi to …" state so every screen can show it.
 */
class NetworkMonitor(context: Context, private val camera: CameraSession, scope: CoroutineScope) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _internet = MutableStateFlow(read(connectivity.activeNetwork))
    val internet: StateFlow<Internet> = _internet
    private val _switching = MutableStateFlow<String?>(null)

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _internet.value = read(network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _internet.value = from(caps)
            }

            override fun onLost(network: Network) {
                _internet.value = Internet()
            }
        })
    }

    val status: StateFlow<NetStatus> = combine(camera.state, _internet, _switching) { cam, net, switching ->
        val online = cam as? CameraState.Online
        NetStatus(
            camera = when {
                online != null -> CameraWifiState.Connected
                cam is CameraState.Working && (cam.message.contains("Join", true) || cam.message.contains("WiFi")) -> CameraWifiState.Joining
                else -> CameraWifiState.Off
            },
            cameraSsid = camera.credentials.value?.ssid,
            cameraRoute = online?.route?.mode,
            internet = net,
            switching = switching,
            dualWifi = camera.dualWifiSupported,
        )
    }.stateIn(scope, SharingStarted.Eagerly, NetStatus())

    fun setSwitching(label: String?) {
        _switching.value = label
    }

    /** Waits up to [timeoutMs] for internet good enough to upload over. */
    suspend fun awaitUsable(allowMobile: Boolean, timeoutMs: Long): Boolean = awaitInternet(timeoutMs) { it.usable(allowMobile) }

    /** Waits up to [timeoutMs] for the internet connection to satisfy [ok]. */
    suspend fun awaitInternet(timeoutMs: Long, ok: (Internet) -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) { _internet.first(ok) } != null

    private fun read(network: Network?): Internet =
        network?.let { connectivity.getNetworkCapabilities(it) }?.let(::from) ?: Internet()

    private fun from(caps: NetworkCapabilities) = Internet(
        connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        // A VPN's capabilities include the transports of the network underneath it.
        wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        mobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
    )
}
