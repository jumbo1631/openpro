package com.openpro.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.openpro.camera.wifi.CameraWifi
import com.openpro.camera.wifi.CameraWifi.Companion.isPermissionDenied
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The bound service OpenPro talks to. On CONNECT it joins the camera's WiFi (as this app, which
 * the user has excluded from their VPN), checks the camera answers, and starts the loopback relay;
 * it replies, and pushes, its state to every client. Only apps signed with the same key may bind
 * (signature permission on the service) or send it messages (checked per message).
 */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wifi: CameraWifi
    private var relay: Relay? = null
    private var preview: com.openpro.camera.wifi.UdpForwarder? = null
    private var connectJob: Job? = null
    private val clients = mutableSetOf<Messenger>()

    override fun onCreate() {
        super.onCreate()
        wifi = CameraWifi(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Camera Link", NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            wifi.network.collect { network ->
                if (network == null && relay != null) {
                    Log.i(TAG, "camera WiFi lost; stopping relay")
                    stopRelay()
                    publish(LinkProtocol.STATE_IDLE, error = "Camera WiFi lost")
                }
            }
        }
    }

    override fun onDestroy() {
        stopRelay()
        wifi.release()
        scope.cancel()
        super.onDestroy()
    }

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!trusted(msg.sendingUid)) {
                Log.w(TAG, "ignoring message from untrusted uid ${msg.sendingUid}")
                return
            }
            msg.replyTo?.let { clients += it }
            when (msg.what) {
                LinkProtocol.MSG_CONNECT -> connect(
                    msg.data.getString(LinkProtocol.KEY_SSID).orEmpty(),
                    msg.data.getString(LinkProtocol.KEY_PASSWORD).orEmpty(),
                    msg.data.getString(LinkProtocol.KEY_BSSID),
                )
                LinkProtocol.MSG_RELEASE -> release()
                LinkProtocol.MSG_STATUS -> publish(currentState)
                LinkProtocol.MSG_PREVIEW_START -> startPreview(msg.data.getInt(LinkProtocol.KEY_PREVIEW_PORT))
                LinkProtocol.MSG_PREVIEW_STOP -> stopPreview()
            }
        }
    })

    private fun trusted(uid: Int): Boolean =
        uid == Process.myUid() || packageManager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH

    override fun onBind(intent: Intent): IBinder = incoming.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_NOT_STICKY
    }

    private var currentState = LinkProtocol.STATE_IDLE

    private fun connect(ssid: String, password: String, bssid: String?) {
        relay?.let { return publish(LinkProtocol.STATE_READY) }
        if (connectJob?.isActive == true) return
        goForeground()
        publish(LinkProtocol.STATE_JOINING)
        connectJob = scope.launch {
            try {
                val network = wifi.connect(ssid, password, bssid)
                val failure = CameraWifi.probeBound(network)
                if (failure != null) {
                    wifi.invalidate()
                    val vpn = failure.isPermissionDenied()
                    publish(
                        LinkProtocol.STATE_FAILED,
                        error = if (vpn) {
                            "Camera Link is also being routed through your VPN. In your VPN app, add " +
                                "\"Camera Link\" to the apps that bypass the VPN, then retry."
                        } else {
                            "Joined $ssid but the camera did not answer (${failure.message}). Check it is awake and retry."
                        },
                        vpnBlocked = vpn,
                    )
                    stopForegroundIfIdle()
                    return@launch
                }
                relay = Relay(network.socketFactory).also { it.start() }
                publish(LinkProtocol.STATE_READY)
            } catch (e: Exception) {
                Log.w(TAG, "connect", e)
                publish(LinkProtocol.STATE_FAILED, error = e.message ?: e.toString())
                stopForegroundIfIdle()
            }
        }
    }

    private fun release() {
        connectJob?.cancel()
        stopRelay()
        wifi.release()
        publish(LinkProtocol.STATE_IDLE)
        stopForegroundIfIdle()
    }

    private fun stopRelay() {
        stopPreview()
        relay?.close()
        relay = null
    }

    private fun startPreview(port: Int) {
        stopPreview()
        val network = wifi.network.value ?: return
        preview = runCatching { com.openpro.camera.wifi.UdpForwarder(network, com.openpro.camera.wifi.UdpForwarder.CAMERA_PREVIEW_PORT, port).start() }
            .onFailure { Log.w(TAG, "preview forwarder", it) }
            .getOrNull()
    }

    private fun stopPreview() {
        preview?.close()
        preview = null
    }

    private fun publish(state: String, error: String? = null, vpnBlocked: Boolean = false) {
        currentState = state
        _status.value = state + (error?.let { ": $it" } ?: "")
        val data = Bundle().apply {
            putInt(LinkProtocol.KEY_VERSION, LinkProtocol.VERSION)
            putString(LinkProtocol.KEY_STATE, state)
            putString(LinkProtocol.KEY_ERROR, error)
            putBoolean(LinkProtocol.KEY_VPN_BLOCKED, vpnBlocked)
            relay?.let {
                putInt(LinkProtocol.KEY_PORT, it.port)
                putString(LinkProtocol.KEY_TOKEN, it.token)
            }
        }
        val dead = mutableListOf<Messenger>()
        for (client in clients) {
            val msg = Message.obtain(null, LinkProtocol.MSG_STATE).apply { this.data = data }
            try {
                client.send(msg)
            } catch (e: Exception) {
                dead += client
            }
        }
        clients -= dead.toSet()
        if (state == LinkProtocol.STATE_READY) updateNotification("Relaying camera WiFi for OpenPro")
    }

    /**
     * Android only lets apps in the foreground (or running a foreground service) request a
     * device's WiFi, and this app has no UI on screen while OpenPro uses it.
     */
    private fun goForeground() {
        runCatching {
            startForeground(NOTIFICATION_ID, notification("Connecting to the camera's WiFi…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }.onFailure { Log.w(TAG, "could not start foreground", it) }
    }

    private fun stopForegroundIfIdle() {
        if (relay == null) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_link)
        .setContentTitle("Camera Link")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    companion object {
        private const val TAG = "LinkService"
        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 1

        private val _status = MutableStateFlow(LinkProtocol.STATE_IDLE)
        /** For the status screen (same process). */
        val status: StateFlow<String> = _status
    }
}
