package com.openpro.link

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * What this app is for, as two diagrams (why it's needed, how it works), and whether a VPN covers
 * it right now, kept up to date as the VPN comes and goes. Nothing to configure here.
 */
class LinkActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private lateinit var cm: ConnectivityManager
    private lateinit var vpnPill: TextView
    private lateinit var how: RouteDiagram

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            runOnUiThread { showVpn(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) }
        }

        override fun onLost(network: Network) {
            runOnUiThread { showVpn(covered()) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(value: String, size: Float = 15f, color: Int = Color.LTGRAY, bottom: Int = 12) = TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            setPadding(0, 0, 0, dp(bottom))
            column.addView(this)
        }
        fun heading(value: String) = text(value, 18f, Color.WHITE, 8).apply { setPadding(0, dp(16), 0, dp(8)) }

        text("Camera Link", 26f, Color.WHITE, 4)
        text("Lets OpenPro reach your GoPro while a VPN is on.", 15f, Color.LTGRAY, 16)

        vpnPill = text("", 15f, Color.WHITE, 0).apply {
            val p = dp(14)
            setPadding(p, dp(10), p, dp(10))
        }

        heading("Why it's needed")
        column.addView(RouteDiagram(this, RouteDiagram.Mode.Without))
        text("A VPN that covers OpenPro stops it from using any other network, and the camera's Wi-Fi is another network.")

        heading("How it works")
        how = RouteDiagram(this, RouteDiagram.Mode.With)
        column.addView(how)
        text(
            "Camera Link joins the camera's Wi-Fi and passes the camera's data to OpenPro inside the phone. " +
                "OpenPro stays on the VPN for your server. Camera Link never connects to anything but the camera.",
        )

        heading("Set up once")
        text(
            "In your VPN app, exclude Camera Link (often called split tunnelling, excluded apps or bypass VPN). " +
                "Keep OpenPro on the VPN. That's all: OpenPro starts Camera Link when it needs it.",
        )
        val status = text("", 14f, Color.GRAY)

        setContentView(ScrollView(this).apply { setBackgroundColor(RouteDiagram.BACKGROUND); addView(column) })

        cm = getSystemService(ConnectivityManager::class.java)
        showVpn(covered())
        cm.registerDefaultNetworkCallback(networkCallback)
        scope.launch { LinkService.status.collect { status.text = "Status: $it" } }
    }

    /** Whether this app's traffic goes through a VPN now (its own default network is the VPN). */
    private fun covered() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun showVpn(covered: Boolean) {
        vpnPill.text = if (covered) {
            "✗  Routed through your VPN: Camera Link can't reach the camera. Exclude it in your VPN app."
        } else {
            "✓  Not routed through a VPN: Camera Link can reach the camera."
        }
        vpnPill.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (covered) 0xFF4A1414.toInt() else 0xFF123A1C.toInt())
            setStroke(dp(1), if (covered) RouteDiagram.RED else RouteDiagram.GREEN)
        }
        how.linkCovered = covered
    }

    override fun onDestroy() {
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
