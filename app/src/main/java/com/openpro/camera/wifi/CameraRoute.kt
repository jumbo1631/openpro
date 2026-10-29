package com.openpro.camera.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.openpro.camera.link.RelayInfo
import com.openpro.camera.wifi.CameraWifi.Companion.isPermissionDenied
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** How sockets reach the camera once the phone is on its access point. */
enum class RouteMode(val label: String) {
    /** Sockets are bound to the camera [Network]: the normal case. */
    Bound("direct"),

    /**
     * Binding is refused (EPERM, typically a VPN) but the default route still reaches 10.5.5.9,
     * e.g. the phone joined the camera from WiFi settings and a VPN lets local traffic bypass it.
     */
    Default("system route"),

    /** Through Camera Link's loopback relay (a VPN covers OpenPro). */
    Relay("via Camera Link"),
}

data class CameraRoute(val network: Network?, val mode: RouteMode, val relay: RelayInfo? = null) {
    val socketFactory: SocketFactory
        get() = if (mode == RouteMode.Bound && network != null) network.socketFactory else SocketFactory.getDefault()

    /** What the engine process should bind to (null = leave routing to the system). */
    val bindNetwork: Network? get() = if (mode == RouteMode.Bound) network else null

    /** Rewrites a camera URL (http://10.5.5.9:8080/...) to go through this route. */
    fun rewrite(url: String): String {
        val r = relay ?: return url
        val prefix = "http://${CameraWifi.HOST}:${CameraWifi.PORT}/"
        return if (url.startsWith(prefix)) "http://127.0.0.1:${r.port}/${r.token}/" + url.removePrefix(prefix) else url
    }

    companion object {
        fun relay(info: RelayInfo) = CameraRoute(null, RouteMode.Relay, info)
    }
}

class CameraUnreachableException(message: String, val vpnBlocked: Boolean = false) : IOException(message)

object CameraReachability {
    private const val TAG = "CameraReachability"

    /**
     * Finds a working way to the camera's HTTP server on [network]: bound to it if allowed,
     * otherwise the default route. Throws with a plain-language explanation if neither works;
     * [CameraUnreachableException.vpnBlocked] says whether Camera Link would help.
     */
    suspend fun probe(context: Context, network: Network): CameraRoute = withContext(Dispatchers.IO) {
        val boundError = CameraWifi.probeBound(network) ?: run {
            Log.i(TAG, "camera reachable on bound network $network")
            return@withContext CameraRoute(network, RouteMode.Bound)
        }
        var defaultError: IOException? = null
        if (boundError.isPermissionDenied()) {
            try {
                Socket().use { it.connect(InetSocketAddress(CameraWifi.HOST, CameraWifi.PORT), 3_000) }
                Log.i(TAG, "bind refused, but default route reaches the camera")
                return@withContext CameraRoute(network, RouteMode.Default)
            } catch (e: IOException) {
                Log.w(TAG, "default-route probe failed: $e")
                defaultError = e
            }
        }
        throw CameraUnreachableException(explain(context, boundError, defaultError), boundError.isPermissionDenied())
    }

    private fun explain(context: Context, bound: IOException, viaDefault: IOException?): String {
        val vpn = vpnActive(context)
        return when {
            bound.isPermissionDenied() && vpn ->
                "Your VPN is blocking the camera's WiFi: Android won't let an app the VPN covers use another network. " +
                    "Disable the VPN, or exclude OpenPro from it in your VPN app. If OpenPro needs the VPN (e.g. to " +
                    "reach your server), install the optional Camera Link and exclude that instead."
            bound.isPermissionDenied() ->
                "Android refused OpenPro access to the camera's WiFi. If you use a VPN or firewall app, " +
                    "exclude OpenPro (or Camera Link) from it, then retry."
            else ->
                "Joined the camera's WiFi but its web server at ${CameraWifi.HOST} did not answer (${(viaDefault ?: bound).message}). " +
                    "Check the camera is awake and retry."
        }
    }

    fun vpnActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}
