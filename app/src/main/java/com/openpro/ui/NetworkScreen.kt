package com.openpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openpro.camera.wifi.CameraReachability
import com.openpro.camera.wifi.RouteMode

/**
 * How OpenPro uses the networks: what's true right now (internet, VPN, Camera Link, how the camera
 * is reached), when Camera Link is used (only when a VPN blocks the camera; nothing to choose), and
 * the mobile data and Wi-Fi switching choices. These apply to everything, the batch included.
 */
@Composable
fun NetworkScreen(vm: AppViewModel, onBack: () -> Unit) {
    val config by vm.batchConfig.collectAsState()
    val net by vm.net.collectAsState()
    val update = vm.batch.settings::update
    val context = LocalContext.current

    Screen("Network", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Heading("Right now", Explainers.network)
            Status("Internet", internetSource(net.internet).replaceFirstChar { it.uppercase() })
            Status("VPN", if (CameraReachability.vpnActive(context)) "On" else "Off")
            Status("Camera Link", if (vm.camera.link.installed) "Installed" else "Not installed")
            Status(
                "Camera",
                when (net.cameraRoute) {
                    null -> "Not connected to its Wi-Fi"
                    RouteMode.Relay -> "Connected through Camera Link"
                    else -> "Connected directly"
                },
            )

            Heading("Camera connection with a VPN", Explainers.cameraConnection(vm))
            Text(
                "OpenPro connects to the camera by itself. Only if your VPN blocks that, and Camera Link is installed, it " +
                    "goes through Camera Link.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Heading("Mobile data and Wi-Fi", null)
            Toggle("Uploads over mobile data", "Off: uploads wait for Wi-Fi.", config.uploadsOverMobileData) { v ->
                update { it.copy(uploadsOverMobileData = v) }
            }
            Toggle(
                "Switch Wi-Fi automatically",
                "On: the batch joins the camera's Wi-Fi to download, and leaves it to upload. Off: it never changes Wi-Fi. " +
                    "Downloads wait until you connect to the camera, and uploads use whatever internet the phone has (mobile " +
                    "data while on the camera's Wi-Fi, if allowed above).",
                config.switchWifi,
            ) { v ->
                update { it.copy(switchWifi = v) }
            }
        }
    }
}

/** One line of the current state: a name, and how it is. */
@Composable
private fun Status(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
