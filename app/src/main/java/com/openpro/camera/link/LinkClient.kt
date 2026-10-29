package com.openpro.camera.link

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.openpro.link.LinkProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** Camera Link's relay, once it is up: requests go to http://127.0.0.1:[port]/[token]/<camera path>. */
data class RelayInfo(val port: Int, val token: String)

class LinkFailedException(message: String, val vpnBlocked: Boolean) : IOException(message)

/**
 * OpenPro's side of Camera Link (see LinkProtocol). Binds to its service, asks it to join
 * the camera's WiFi, and tracks the relay it reports.
 */
class LinkClient(private val context: Context) {
    data class Status(val state: String = LinkProtocol.STATE_IDLE, val relay: RelayInfo? = null, val error: String? = null, val vpnBlocked: Boolean = false)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    private var service: Messenger? = null
    private var bound = false
    private val serviceReady = MutableStateFlow(false)

    val installed: Boolean
        get() = try {
            context.packageManager.getPackageInfo(LinkProtocol.PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != LinkProtocol.MSG_STATE) return
            val d = msg.data
            val port = d.getInt(LinkProtocol.KEY_PORT, 0)
            val token = d.getString(LinkProtocol.KEY_TOKEN)
            _status.value = Status(
                state = d.getString(LinkProtocol.KEY_STATE) ?: LinkProtocol.STATE_IDLE,
                relay = if (port > 0 && token != null) RelayInfo(port, token) else null,
                error = d.getString(LinkProtocol.KEY_ERROR),
                vpnBlocked = d.getBoolean(LinkProtocol.KEY_VPN_BLOCKED),
            )
            Log.i(TAG, "link state: ${_status.value.state} ${_status.value.error ?: ""}")
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            serviceReady.value = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // Camera Link's process died: its relay and camera connection are gone.
            service = null
            serviceReady.value = false
            _status.value = Status(error = "Camera Link stopped")
        }
    }

    private val intent get() = Intent().setClassName(LinkProtocol.PACKAGE, LinkProtocol.SERVICE)

    private suspend fun ensureBound(): Messenger {
        if (!bound) {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES)
            if (!bound) throw LinkFailedException("Couldn't start Camera Link. Reinstall it from the same build as OpenPro.", false)
        }
        withTimeout(10_000) { serviceReady.first { it } }
        return service ?: throw LinkFailedException("Camera Link is not responding", false)
    }

    private fun send(target: Messenger, what: Int, data: Bundle = Bundle()) {
        target.send(Message.obtain(null, what).apply { this.data = data; replyTo = replies })
    }

    /** Asks Camera Link to join the camera and start relaying. Allows time for Android's join prompt. */
    suspend fun connect(ssid: String, password: String, apMac: String?): RelayInfo {
        val target = ensureBound()
        // Camera Link must run as a foreground service to be allowed to request the camera's WiFi.
        runCatching { context.startForegroundService(intent) }.onFailure { Log.w(TAG, "start link service", it) }
        _status.value = Status(state = LinkProtocol.STATE_JOINING)
        send(target, LinkProtocol.MSG_CONNECT, Bundle().apply {
            putString(LinkProtocol.KEY_SSID, ssid)
            putString(LinkProtocol.KEY_PASSWORD, password)
            putString(LinkProtocol.KEY_BSSID, apMac)
        })
        val result = withTimeout(150_000) {
            status.first { it.state == LinkProtocol.STATE_READY || it.state == LinkProtocol.STATE_FAILED }
        }
        return result.relay ?: throw LinkFailedException(result.error ?: "Camera Link couldn't reach the camera", result.vpnBlocked)
    }

    /** Asks Camera Link to forward the camera's live preview to 127.0.0.1:[port]. */
    fun startPreview(port: Int) {
        service?.let { send(it, LinkProtocol.MSG_PREVIEW_START, Bundle().apply { putInt(LinkProtocol.KEY_PREVIEW_PORT, port) }) }
            ?: throw LinkFailedException("Camera Link isn't connected", false)
    }

    fun stopPreview() {
        service?.let { runCatching { send(it, LinkProtocol.MSG_PREVIEW_STOP) } }
    }

    fun release() {
        service?.let { runCatching { send(it, LinkProtocol.MSG_RELEASE) } }
        _status.value = Status()
    }

    companion object {
        private const val TAG = "LinkClient"
    }
}
