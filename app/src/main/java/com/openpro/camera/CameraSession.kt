package com.openpro.camera

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.openpro.camera.ble.FoundCamera
import com.openpro.camera.ble.GoProBle
import com.openpro.camera.ble.HardwareInfo
import com.openpro.camera.http.CameraApi
import com.openpro.camera.link.LinkClient
import com.openpro.camera.link.LinkFailedException
import com.openpro.camera.wifi.CameraReachability
import com.openpro.camera.wifi.CameraRoute
import com.openpro.camera.wifi.CameraWifi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CameraState {
    data object Disconnected : CameraState
    data class Working(val message: String) : CameraState
    /** BLE control link up; WiFi off. */
    data class Ready(val info: HardwareInfo, val model: CameraModel?) : CameraState
    /** WiFi AP joined; media endpoints reachable through [route]. */
    data class Online(val info: HardwareInfo, val model: CameraModel?, val route: CameraRoute) : CameraState
    data class Failed(val message: String) : CameraState
}

/**
 * Owns the camera connection. Bluetooth is the control channel (wake, settings, keep-alive,
 * turning the WiFi AP on/off). GoPro cameras only serve media over WiFi, so the AP is enabled on
 * demand for browsing/transfers and switched off again with [goOffline].
 */
class CameraSession(private val context: Context, private val scope: CoroutineScope) {
    val ble = GoProBle(context)
    private val wifi = CameraWifi(context)
    val link = LinkClient(context)
    private val prefs = context.getSharedPreferences("camera", Context.MODE_PRIVATE)
    /** The cameras the user has added. */
    val saved = CameraStore(context)
    private val lock = Mutex()

    /** The camera being connected to, or connected (its Bluetooth address); null when none. */
    private val _address = MutableStateFlow<String?>(null)
    val address: StateFlow<String?> = _address

    /** A camera just added for the first time, for the "name your camera" prompt; cleared by [namePrompted]. */
    private val _justAdded = MutableStateFlow<String?>(null)
    val justAdded: StateFlow<String?> = _justAdded

    fun namePrompted() {
        _justAdded.value = null
    }

    /** The added camera that's connected right now, if any. */
    val current: SavedCamera?
        get() = if (state.value is CameraState.Ready || state.value is CameraState.Online) saved.get(_address.value) else null
    private var keepAliveJob: Job? = null

    private val _state = MutableStateFlow<CameraState>(CameraState.Disconnected)
    val state: StateFlow<CameraState> = _state

    var api: CameraApi? = null
        private set

    /** Camera AP credentials from the last attempt, for joining by hand from WiFi settings. */
    data class WifiCredentials(val ssid: String, val password: String)
    private val _credentials = MutableStateFlow<WifiCredentials?>(null)
    val credentials: StateFlow<WifiCredentials?> = _credentials

    /**
     * Screens that show camera content (gallery, clip, remote) hold this while visible, so the batch
     * doesn't switch the phone away from the camera's WiFi under the user.
     */
    private val _uiHolds = MutableStateFlow(0)
    val uiHolds: StateFlow<Int> = _uiHolds

    fun holdForUi(): () -> Unit {
        _uiHolds.value++
        var released = false
        return { if (!released) { released = true; _uiHolds.value-- } }
    }

    /** True when the phone can be on its normal WiFi and the camera's at once (no switching needed). */
    val dualWifiSupported: Boolean get() = wifi.dualWifiSupported

    init {
        // If Android drops the camera network, fall back to Bluetooth-only instead of keeping a dead handle.
        scope.launch {
            wifi.network.collect { network ->
                val online = state.value as? CameraState.Online
                if (network == null && online != null && online.route.mode != com.openpro.camera.wifi.RouteMode.Relay) {
                    Log.i(TAG, "camera WiFi lost")
                    api = null
                    _state.value = CameraState.Ready(online.info, online.model)
                }
            }
        }
        // Same for Camera Link losing the camera (or its process dying).
        scope.launch {
            link.status.collect { status ->
                val online = state.value as? CameraState.Online
                if (status.relay == null && online != null && online.route.mode == com.openpro.camera.wifi.RouteMode.Relay) {
                    Log.i(TAG, "Camera Link lost the camera: ${status.error}")
                    api = null
                    _state.value = CameraState.Ready(online.info, online.model)
                }
            }
        }
    }

    fun scan(): Flow<FoundCamera> = ble.scan()

    /**
     * Connects to the camera. A camera OpenPro doesn't support is reported as such: from
     * [advertisedModelId] before anything happens (so it isn't paired for nothing), otherwise once it
     * says what it is, after which it's disconnected and not remembered for reconnecting.
     */
    fun connect(device: BluetoothDevice, name: String, advertisedModelId: Int? = null) = scope.launch {
        connecting = coroutineContext[Job]
        lock.withLock {
            _address.value = device.address
            if (advertisedModelId != null && !CameraModels.isUsable(advertisedModelId)) {
                _state.value = CameraState.Failed(CameraModels.unsupportedMessage(advertisedModelId))
                return@withLock
            }
            try {
                _state.value = CameraState.Working("Connecting to $name over Bluetooth…")
                val info = connectAndIdentify(device, name)
                if (!CameraModels.isUsable(info.modelId)) {
                    Log.i(TAG, "unsupported camera: $info")
                    ble.disconnect()
                    _state.value = CameraState.Failed(CameraModels.unsupportedMessage(info.modelId) + " (Firmware ${info.firmware}.)")
                    return@withLock
                }
                val (_, isNew) = saved.connected(device.address, name, info)
                if (isNew) _justAdded.value = device.address
                _state.value = CameraState.Ready(info, CameraModels.byModelId(info.modelId))
                startKeepAlive()
            } catch (e: Exception) {
                ble.disconnect()
                // Cancelled by the user (cancelConnecting): not a failure. A timeout is one, though
                // Kotlin reports it as a cancellation too.
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                Log.w(TAG, "connect", e)
                _state.value = CameraState.Failed(
                    "$name isn't answering over Bluetooth (${e.message ?: e}). If it was asleep or just turned on, " +
                        "give it a few seconds and reconnect; if this keeps happening, turn the camera off and on. " +
                        "GoPros older than the HERO9 Black may not answer at all: OpenPro doesn't support them.",
                )
            }
        }
    }

    /**
     * Connects and asks the camera what it is. A camera that has just woken up can stall or answer
     * "error" for a few seconds, so [identify] asks again while it starts up. If it still won't
     * answer, the link has to drop completely before trying again: after close() Android keeps
     * the Bluetooth link up for a few seconds, and reconnecting within that time reuses the
     * camera's stuck session (it only resets when the link really drops). Restarting the app
     * happened to take long enough, which is why that used to be the only cure.
     */
    private suspend fun connectAndIdentify(device: BluetoothDevice, name: String): HardwareInfo {
        var failure: Exception? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                _state.value = CameraState.Working("$name isn't answering yet. Reconnecting from scratch…")
                delay(LINK_DROP_MS)
            }
            try {
                ble.connect(device)
                return identify(name)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                Log.w(TAG, "connect attempt ${attempt + 1} of $CONNECT_ATTEMPTS", e)
                failure = e
                ble.disconnect()
            }
        }
        throw failure!!
    }

    /** Hardware info, asked again a few times while the camera answers "error" (still starting up). */
    private suspend fun identify(name: String): HardwareInfo {
        var failure: com.openpro.camera.ble.BleException? = null
        for (wait in IDENTIFY_RETRY_MS) {
            if (wait > 0) {
                _state.value = CameraState.Working("Waiting for $name to be ready…")
                delay(wait)
            }
            try {
                return ble.hardwareInfo()
            } catch (e: com.openpro.camera.ble.BleException) {
                // A timeout means the link itself is stuck; that's for connectAndIdentify to handle.
                Log.i(TAG, "hardware info: ${e.message}")
                failure = e
                if (!ble.connected.value) break
            }
        }
        throw failure!!
    }

    /** The connection over Bluetooth in progress, if any, so it can be cancelled. */
    @Volatile private var connecting: Job? = null

    /**
     * Stops connecting: over Bluetooth (back to not connected), or joining the camera's Wi-Fi
     * (back to connected over Bluetooth only).
     */
    fun cancelConnecting() {
        val job = connecting?.takeIf { it.isActive }
        if (job != null) {
            job.cancel()
            scope.launch {
                job.join()
                _address.value = null
                _state.value = CameraState.Disconnected
            }
            return
        }
        val join = synchronized(this) { joining }?.takeIf { it.isActive }
        if (join != null) {
            join.cancel()
            return
        }
        // Nothing running, yet it still says it's connecting: don't leave it stuck there.
        if (_state.value is CameraState.Working) {
            _address.value = null
            _state.value = CameraState.Disconnected
        }
    }

    fun reconnectLast() {
        saved.last?.let { reconnect(it.address) }
    }

    /** Connects to a camera the user added before. */
    fun reconnect(address: String) {
        val camera = saved.get(address) ?: return
        connect(ble.deviceFor(address), camera.name)
    }

    /** Removes an added camera; disconnects first if it's the one connected. */
    fun forget(address: String) {
        if (_address.value == address) disconnect(sleepCamera = false)
        saved.forget(address)
    }

    /** The join in progress, shared by everyone who needs the camera's Wi-Fi meanwhile. */
    private var joining: Deferred<CameraApi>? = null

    /**
     * Turns the camera AP on over BLE and joins it. Returns the API client.
     *
     * The join runs in the app's scope and is shared: a caller that goes away (the user leaves the
     * screen that asked) stops waiting but doesn't abort it. Aborting halfway left the camera's
     * Wi-Fi half set up and made the next attempt start over, which is most of what made joining
     * slow and erratic.
     */
    suspend fun goOnline(): CameraApi {
        (state.value as? CameraState.Online)?.let { api?.let { a -> return a } }
        val join = synchronized(this) {
            joining?.takeIf { it.isActive } ?: scope.async { join() }.also { joining = it }
        }
        return join.await()
    }

    private suspend fun join(): CameraApi = lock.withLock {
        (state.value as? CameraState.Online)?.let { api?.let { a -> return a } }
        val ready = state.value as? CameraState.Ready
            ?: throw IllegalStateException("Connect to the camera over Bluetooth first")
        try {
            whileBluetoothConnected { joinCamera(ready) }
        } catch (e: Exception) {
            // Back to Bluetooth-only, unless the camera itself went away (crashed, turned off).
            _state.value = if (ble.connected.value) {
                ready
            } else {
                CameraState.Failed("The camera disconnected. It may have turned off or crashed; wake it, then reconnect.")
            }
            throw e
        }
    }

    /** Runs [block], failing at once (instead of waiting out Wi-Fi timeouts) if the Bluetooth link drops. */
    private suspend fun <T> whileBluetoothConnected(block: suspend () -> T): T = kotlinx.coroutines.coroutineScope {
        val lost = async {
            ble.connected.first { !it }
            throw com.openpro.camera.ble.BleException("Lost the Bluetooth connection to the camera while joining its Wi-Fi. It may have turned off or crashed.")
        }
        try {
            block()
        } finally {
            lost.cancel()
        }
    }

    private suspend fun joinCamera(ready: CameraState.Ready): CameraApi {
        run {
            _state.value = CameraState.Working("Turning on camera WiFi…")
            ble.setWifiAp(true)
            val ssid = ble.wifiSsid().ifEmpty { ready.info.apSsid }
            val password = ble.wifiPassword()
            _credentials.value = WifiCredentials(ssid, password)
            // Android's join dialog scans once; asking before the AP is broadcasting is the usual
            // reason it spins or fails, so wait for the camera to report the AP as up.
            _state.value = CameraState.Working("Waiting for the camera's WiFi to start…")
            ble.waitForWifiAp()
            val route = when (chooseRoute()) {
                RouteChoice.Link -> joinViaLink(ssid, password, ready.info.apMac)
                RouteChoice.Direct -> try {
                    joinDirect(ssid, password, ready.info.apMac).also { vpnBlocksDirect = false }
                } catch (e: com.openpro.camera.wifi.CameraUnreachableException) {
                    // A VPN refused direct access; Camera Link (excluded from the VPN) can still get there.
                    if (!e.vpnBlocked || !link.installed) throw e
                    Log.i(TAG, "direct access blocked by VPN; trying Camera Link")
                    vpnBlocksDirect = true
                    wifi.release()
                    joinViaLink(ssid, password, ready.info.apMac)
                }
            }
            Log.i(TAG, "camera route: ${route.mode}")
            val client = CameraApi(route)
            api = client
            _state.value = CameraState.Online(ready.info, ready.model, route)
            return client
        }
    }

    private enum class RouteChoice { Direct, Link }

    /**
     * Direct, always first. Camera Link (if installed) is used only after a direct attempt was
     * refused by a VPN, and then straight away while that VPN stays on (so the user isn't asked to
     * approve two joins every time).
     */
    private fun chooseRoute(): RouteChoice {
        val vpn = com.openpro.camera.wifi.CameraReachability.vpnActive(context)
        if (!vpn) vpnBlocksDirect = false
        return if (vpn && vpnBlocksDirect && link.installed) RouteChoice.Link else RouteChoice.Direct
    }

    /** A VPN refused OpenPro's own connection to the camera (remembered while a VPN is on). */
    private var vpnBlocksDirect: Boolean
        get() = prefs.getBoolean("vpnBlocksDirect", false)
        set(value) = prefs.edit().putBoolean("vpnBlocksDirect", value).apply()

    private suspend fun joinDirect(ssid: String, password: String, apMac: String): com.openpro.camera.wifi.CameraRoute {
        _state.value = CameraState.Working("Joining $ssid…")
        val network = try {
            wifi.connect(ssid, password, apMac) {
                _state.value = CameraState.Working("Joining $ssid… If Android asks to connect to a device, choose $ssid and tap Connect.")
            }
        } catch (e: java.io.IOException) {
            // Say whether the camera's Wi-Fi was even on, which is the usual reason a join fails.
            if (ble.wifiApOn() == false) {
                throw java.io.IOException("The camera's Wi-Fi didn't come on. If the camera seems frozen, restart it, then reconnect.", e)
            }
            throw e
        }
        _state.value = CameraState.Working("Checking the camera is reachable…")
        return CameraReachability.probe(context, network)
    }

    private suspend fun joinViaLink(ssid: String, password: String, apMac: String): com.openpro.camera.wifi.CameraRoute {
        if (!link.installed) {
            throw LinkFailedException(
                "Your VPN blocks the camera, and Camera Link isn't installed. Install Camera Link, or turn off the VPN " +
                    "or exclude OpenPro from it.",
                vpnBlocked = false,
            )
        }
        _state.value = CameraState.Working("Joining $ssid via Camera Link…")
        return com.openpro.camera.wifi.CameraRoute.relay(link.connect(ssid, password, apMac))
    }

    /**
     * Called when a request on the camera network fails with EPERM/ENONET: the handle is no longer
     * ours to use (request released, or replaced by a system connection). Drop it; [goOnline] re-acquires.
     */
    suspend fun networkFailed() = lock.withLock {
        val online = state.value as? CameraState.Online ?: return@withLock
        api = null
        wifi.invalidate()
        link.release()
        _state.value = CameraState.Ready(online.info, online.model)
    }

    /**
     * Leaves the camera's WiFi but keeps the camera's AP on, so the phone returns to its normal
     * WiFi (for uploads) and can rejoin the camera quickly afterwards.
     */
    suspend fun parkWifi() = lock.withLock {
        val online = state.value as? CameraState.Online ?: return@withLock
        api = null
        wifi.release()
        link.release()
        _state.value = CameraState.Ready(online.info, online.model)
    }

    /** Leaves the camera AP and switches it off to save the camera's battery. */
    suspend fun goOffline() = lock.withLock {
        val online = state.value as? CameraState.Online ?: return@withLock
        api = null
        wifi.release()
        link.release()
        runCatching { ble.setWifiAp(false) }
        _state.value = CameraState.Ready(online.info, online.model)
    }

    fun disconnect(sleepCamera: Boolean) = scope.launch {
        synchronized(this@CameraSession) { joining }?.cancel()
        lock.withLock {
            keepAliveJob?.cancel()
            api = null
            wifi.release()
            link.release()
            if (sleepCamera) ble.sleep()
            ble.disconnect()
            _address.value = null
            _state.value = CameraState.Disconnected
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(KEEP_ALIVE_MS)
                if (!ble.connected.value) {
                    api = null
                    wifi.release()
                    link.release()
                    _state.value = CameraState.Failed("Camera disconnected")
                    break
                }
                runCatching { ble.keepAlive() }.onFailure { Log.w(TAG, "keep-alive", it) }
            }
        }
    }

    companion object {
        private const val TAG = "CameraSession"
        private const val KEEP_ALIVE_MS = 3_000L
        private const val CONNECT_ATTEMPTS = 2
        /** Longer than Android keeps an unused Bluetooth link up after close() (4 s). */
        private const val LINK_DROP_MS = 6_000L
        private val IDENTIFY_RETRY_MS = longArrayOf(0, 1_000, 2_000, 3_000)
    }
}
