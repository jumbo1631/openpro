package com.openpro.camera.remote

import android.util.Log
import com.openpro.camera.CameraSession
import com.openpro.camera.CameraState
import com.openpro.camera.http.CameraApi
import com.openpro.camera.wifi.RouteMode
import com.openpro.camera.wifi.UdpForwarder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * The camera's live preview (Open GoPro HTTP: /gopro/camera/stream/start). The camera sends an
 * MPEG-TS stream over UDP to the phone's port 8554 on its own WiFi; that is forwarded to a loopback
 * port the player listens on, either by OpenPro or (when a VPN covers OpenPro) by Camera Link.
 */
class PreviewStream(private val camera: CameraSession) {
    private var forwarder: UdpForwarder? = null
    private var viaLink = false

    /** Starts the stream (joining the camera's WiFi if needed); returns the loopback port to play. */
    suspend fun start(): Int {
        val api = camera.goOnline()
        val route = (camera.state.value as? CameraState.Online)?.route ?: throw IOException("The camera's WiFi isn't connected")
        stopForwarding()
        val port = withContext(Dispatchers.IO) { UdpForwarder.freeLoopbackPort() }
        if (route.mode == RouteMode.Relay) {
            camera.link.startPreview(port)
            viaLink = true
        } else {
            forwarder = withContext(Dispatchers.IO) { UdpForwarder(route.bindNetwork, UdpForwarder.CAMERA_PREVIEW_PORT, port).start() }
        }
        // A stream left running from earlier makes start fail on some cameras; stop it first.
        runCatching { get(api, "gopro/camera/stream/stop") }
        val code = get(api, "gopro/camera/stream/start")
        if (code !in 200..299) {
            stopForwarding()
            throw IOException(
                if (code == 404) "This camera doesn't offer a live preview over WiFi (Open GoPro preview not supported)"
                else "The camera refused to start the preview (HTTP $code)",
            )
        }
        Log.i(TAG, "preview streaming to loopback port $port (${route.mode})")
        return port
    }

    suspend fun stop() {
        camera.api?.let { api -> runCatching { get(api, "gopro/camera/stream/stop") } }
        stopForwarding()
    }

    /** Datagrams forwarded so far (direct mode), to tell "no data" from "can't decode". */
    val packets: Long get() = forwarder?.packets ?: -1

    private fun stopForwarding() {
        forwarder?.close()
        forwarder = null
        if (viaLink) camera.link.stopPreview()
        viaLink = false
    }

    private suspend fun get(api: CameraApi, path: String): Int = withContext(Dispatchers.IO) {
        api.client.newCall(Request.Builder().url(CameraApi.url(path)).build()).execute().use { it.code }
    }

    companion object {
        private const val TAG = "PreviewStream"
    }
}
