package com.openpro.link

import java.net.InetAddress

/**
 * Messages between OpenPro and Camera Link (com.openpro.link), compiled into both apps.
 *
 * Why there are two apps: a VPN that doesn't allow bypassing makes Android refuse every socket an
 * app binds to a non-VPN network, decided per app (Linux UID). Camera Link is a separate app, so the
 * user can exclude it from the VPN while OpenPro stays on the VPN for uploads. Camera Link joins the
 * camera's WiFi and relays the camera's HTTP server on 127.0.0.1; loopback is never routed through
 * a VPN. Requests must start with /<token>/ (a random per-session secret handed over the bound,
 * signature-protected service), so other apps on the phone can't use the relay.
 */
object LinkProtocol {
    const val PACKAGE = "com.openpro.link"
    const val SERVICE = "com.openpro.link.LinkService"
    const val PERMISSION = "com.openpro.permission.CAMERA_LINK"
    const val VERSION = 1

    /**
     * 127.0.0.1, where both apps meet. Not InetAddress.getLoopbackAddress(): on Android that is the
     * IPv6 ::1, which a socket listening on 127.0.0.1 never hears.
     */
    val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    // OpenPro -> Camera Link
    const val MSG_CONNECT = 1
    const val MSG_RELEASE = 2
    const val MSG_STATUS = 3
    /** Forward the camera's live preview (UDP 8554) to 127.0.0.1:[KEY_PREVIEW_PORT]. */
    const val MSG_PREVIEW_START = 4
    const val MSG_PREVIEW_STOP = 5

    // Camera Link -> OpenPro (reply to any message, and pushed on every change)
    const val MSG_STATE = 100

    const val KEY_SSID = "ssid"
    const val KEY_PASSWORD = "password"
    const val KEY_BSSID = "bssid"
    const val KEY_STATE = "state"
    const val KEY_PORT = "port"
    const val KEY_TOKEN = "token"
    const val KEY_ERROR = "error"
    const val KEY_VPN_BLOCKED = "vpnBlocked"
    const val KEY_VERSION = "version"
    const val KEY_PREVIEW_PORT = "previewPort"

    const val STATE_IDLE = "idle"
    const val STATE_JOINING = "joining"
    const val STATE_READY = "ready"
    const val STATE_FAILED = "failed"

    const val CAMERA_HOST = "10.5.5.9"
    const val CAMERA_PORT = 8080
}
