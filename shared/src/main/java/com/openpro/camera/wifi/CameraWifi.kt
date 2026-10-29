package com.openpro.camera.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress

/**
 * Gets this app onto the camera's access point (shared by OpenPro and Camera Link).
 *
 * If the phone is already joined to the camera AP as an ordinary WiFi connection (e.g. from system
 * settings), that network is used directly: any app may bind to it. Otherwise this asks for a
 * private, app-only connection with a WifiNetworkSpecifier, leaving the phone's internet untouched.
 * A specifier network is only usable by this app while its request is held; using a handle after
 * the request is gone (or after the system replaced it) fails with EPERM, so [invalidate] drops it.
 *
 * Releasing the request is how the phone "switches back to internet": Android rejoins the user's
 * saved WiFi by itself. Asking again normally shows Android's approval dialog; it is skipped when
 * the request names the exact SSID and BSSID of an access point the user approved before, so
 * requests are pinned to the camera's AP MAC address (from Bluetooth) when it's known.
 */
class CameraWifi(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    /** The current request's (or watch's) callback; callbacks of requests since dropped are ignored. */
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network

    /**
     * True if this phone can stay on its normal WiFi while also connected to the camera, so
     * internet keeps working during camera transfers and no switching is needed.
     */
    val dualWifiSupported: Boolean
        get() = runCatching { wifiManager.isStaConcurrencyForLocalOnlyConnectionsSupported }.getOrDefault(false)

    /** Joins the camera's AP. [onPrompt] is called first: Android's approval prompt may appear. */
    suspend fun connect(ssid: String, password: String, apMac: String? = null, onPrompt: () -> Unit = {}): Network {
        _network.value?.let { return it }
        release()
        cameraNetworkAlreadyJoined()?.let { existing ->
            Log.i(TAG, "phone is already on the camera AP ($existing); using it")
            watch(existing)
            _network.value = existing
            return existing
        }
        onPrompt()
        val bssid = parseMac(apMac)
        if (bssid != null) {
            // Pinned to the camera's AP: once the user has approved this exact AP, Android
            // connects without a prompt; until then its prompt lists just this camera. One long
            // request, since giving up early cancels a prompt the user may be about to tap.
            request(ssid, password, bssid, timeoutMs = 60_000)?.let { return it }
            Log.w(TAG, "pinned request for $ssid/$bssid found nothing; asking without BSSID")
            // Android's prompt for the released request takes a moment to close. A new request
            // made before then gets its prompt closed along with the old one, and Android then
            // finds the camera with nowhere to show it ("No callback registered"), so wait.
            delay(PROMPT_CLOSE_MS)
        }
        return request(ssid, password, null, timeoutMs = 90_000)
            ?: throw IOException("Could not join $ssid (declined, or the camera's WiFi is out of range)")
    }

    private suspend fun request(ssid: String, password: String, bssid: MacAddress?, timeoutMs: Int): Network? {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .apply { if (bssid != null) setBssid(bssid) }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val available = CompletableDeferred<Network?>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (callback !== this) return // a request we've since dropped
                Log.i(TAG, "app-only camera network available: $network (pinned=${bssid != null})")
                _network.value = network
                available.complete(network)
            }

            override fun onUnavailable() {
                available.complete(null)
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "camera network lost: $network")
                if (callback === this && _network.value == network) _network.value = null
            }
        }
        callback = cb
        connectivity.requestNetwork(request, cb, timeoutMs)
        val network = try {
            available.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The join was abandoned: withdraw the request, or Android would carry on joining
            // the camera for nobody (and its callbacks would land in a later join).
            drop(cb)
            throw e
        }
        if (network == null) drop(cb) // timed out or declined
        return network
    }

    private fun drop(cb: ConnectivityManager.NetworkCallback) {
        runCatching { connectivity.unregisterNetworkCallback(cb) }
        if (callback === cb) callback = null
    }

    /** A WiFi network the phone already has whose address is on the camera's 10.5.5.0/24 subnet. */
    private fun cameraNetworkAlreadyJoined(): Network? {
        @Suppress("DEPRECATION") // allNetworks is the only synchronous way to enumerate.
        val networks = connectivity.allNetworks
        return networks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                connectivity.getLinkProperties(network)?.isOnCameraSubnet() == true
        }
    }

    private fun LinkProperties.isOnCameraSubnet() = linkAddresses.any { la ->
        val a = la.address
        a is Inet4Address && a.address[0] == 10.toByte() && a.address[1] == 5.toByte() && a.address[2] == 5.toByte()
    }

    /** Tracks loss of a network we did not request ourselves. */
    private fun watch(network: Network) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) {
                if (lost == network && _network.value == network) _network.value = null
            }
        }
        callback = cb
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb,
        )
    }

    /** Forget the current network (e.g. after EPERM) so the next [connect] starts fresh. */
    fun invalidate() = release()

    /** Lets go of the camera network; Android returns the phone to its saved WiFi. */
    fun release() {
        // With where it was called from, so an unexpected drop of the camera's Wi-Fi can be traced.
        if (callback != null) Log.d(TAG, "releasing the camera network", Exception("released from"))
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        _network.value = null
    }

    companion object {
        private const val TAG = "CameraWifi"
        const val HOST = "10.5.5.9"
        const val PORT = 8080
        private const val PROMPT_CLOSE_MS = 2_000L

        /** GoPro reports its AP MAC as 12 hex digits, sometimes with separators. */
        fun parseMac(raw: String?): MacAddress? {
            val hex = raw?.filter { it.isLetterOrDigit() }?.lowercase() ?: return null
            if (hex.length != 12 || hex.any { it !in "0123456789abcdef" } || hex == "000000000000") return null
            return runCatching { MacAddress.fromString(hex.chunked(2).joinToString(":")) }.getOrNull()
        }

        fun Throwable.isPermissionDenied(): Boolean =
            generateSequence(this) { it.cause }.any { "EPERM" in (it.message ?: "") || "Operation not permitted" in (it.message ?: "") }

        /**
         * Opens a socket to the camera's web server on [network]. The server can take a few seconds
         * to answer after the AP comes up, so failures are retried briefly; a refused bind (EPERM,
         * a VPN) is returned at once since it won't change.
         */
        suspend fun probeBound(network: Network): IOException? = withContext(Dispatchers.IO) {
            var last: IOException? = null
            for (attempt in 0 until 6) {
                if (attempt > 0) delay(1_500)
                try {
                    network.socketFactory.createSocket().use { it.connect(InetSocketAddress(HOST, PORT), 3_000) }
                    return@withContext null
                } catch (e: IOException) {
                    Log.w(TAG, "bound probe failed (attempt $attempt): $e")
                    last = e
                    if (e.isPermissionDenied()) break
                }
            }
            last
        }
    }
}
