package com.openpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openpro.camera.CameraState
import com.openpro.camera.http.CameraTraffic
import com.openpro.net.Internet

/** The app's view model, for what every screen shows whichever screen it is (the connection bar). */
val LocalAppViewModel = staticCompositionLocalOf<AppViewModel?> { null }

/**
 * The one place that shows the phone's connections, under the title on every screen. On the left,
 * the internet and where it comes from (Wi-Fi, mobile data, a VPN), just for information. On the
 * right, where a thumb reaches it, the camera: its name, whether the phone is connected to its
 * Wi-Fi, and a switch to connect and disconnect; a spinner and Cancel while that changes (whether
 * you or the batch changed it); "Error" (tap for why) when it failed. Thin lines along the bottom
 * show data moving: from the camera (blue), and up to a server (red).
 */
@Composable
fun ConnectionBar() {
    val vm = LocalAppViewModel.current ?: return
    val state by vm.cameraState.collectAsState()
    val net by vm.net.collectAsState()
    val cameras by vm.camera.saved.cameras.collectAsState()
    val address by vm.camera.address.collectAsState()
    val error by vm.mediaError.collectAsState()
    val leaving by vm.leavingWifi.collectAsState()
    val live by vm.batch.live.collectAsState()
    val bluetooth by vm.camera.ble.connected.collectAsState()
    val wifiWanted by vm.wifiWanted.collectAsState()
    val traffic by CameraTraffic.snapshot.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            CameraTraffic.tick()
        }
    }
    var showError by remember { mutableStateOf(false) }

    val colors = MaterialTheme.colorScheme
    val camera = cameras.firstOrNull { it.address == address } ?: vm.camera.saved.last
    val online = state is CameraState.Online
    // Only the camera's Wi-Fi: joining it (Bluetooth is up by then), or connecting over Bluetooth on
    // the way to it when that was asked for; not Connect on the home screen, which is Bluetooth alone.
    // The batch switching networks counts too: leaving the camera's Wi-Fi for the internet, or joining it.
    val switching = when {
        state is CameraState.Working && (bluetooth || wifiWanted) -> "Connecting…"
        leaving -> "Disconnecting…"
        net.switching != null -> if (online) "Disconnecting…" else "Connecting…"
        else -> null
    }
    // Failing to get onto the camera's Wi-Fi; a failed Bluetooth connection is the home screen's to show.
    val failure = error?.takeIf { !online && switching == null }
    val showsError = failure != null && switching == null && camera != null

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Internet: where it's coming from. Information only.
            val internet = net.internet.usable(allowMobile = true)
            Icon(
                if (internet) Icons.Filled.Public else Icons.Filled.PublicOff, null,
                Modifier.size(20.dp), tint = if (internet) colors.tertiary else colors.outline,
            )
            TwoLines("Internet", internetSource(net.internet), if (internet) colors.onSurfaceVariant else colors.outline)

            VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 10.dp))

            // The camera: connected to its Wi-Fi or not, and the switch.
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                when {
                    switching != null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    online -> Icon(Icons.Filled.Wifi, null, tint = colors.primary)
                    else -> Icon(Icons.Filled.WifiOff, null, tint = if (showsError) colors.error else colors.onSurfaceVariant)
                }
            }
            Row(
                Modifier.weight(1f).then(if (showsError) Modifier.clickable { showError = true } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TwoLines(
                    camera?.name ?: "No camera added",
                    when {
                        camera == null -> null
                        switching != null -> switching
                        online -> "Connected to camera Wi-Fi"
                        showsError -> "Error"
                        else -> "Not connected"
                    },
                    when {
                        online && switching == null -> colors.primary
                        showsError -> colors.error
                        else -> colors.onSurfaceVariant
                    },
                    trailing = if (showsError) {
                        { Icon(Icons.Filled.ArrowOutward, "Why it failed", Modifier.size(14.dp), tint = colors.error) }
                    } else null,
                )
            }
            if (switching != null) {
                OutlinedButton(
                    onClick = { vm.cancelConnecting() }, Modifier.height(32.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Switch(
                    checked = online,
                    onCheckedChange = { on -> if (on) vm.connectCamera() else vm.goOffline() },
                    enabled = camera != null,
                )
            }
        }
        // Data moving: from the camera, and up to a server.
        if (traffic.busy || traffic.bytesPerSecond > 0) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.primary)
        if (live.values.any { it.toInternet }) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.tertiary)
        HorizontalDivider()
    }

    if (showError && failure != null) {
        AlertDialog(
            onDismissRequest = { showError = false },
            title = { Text("Couldn't connect") },
            text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(failure) } },
            confirmButton = { TextButton(onClick = { showError = false; vm.connectCamera() }) { Text("Try again") } },
            dismissButton = { TextButton(onClick = { showError = false }) { Text("Close") } },
        )
    }
}

/** Where the phone's internet comes from, for the connection bar: "via Wi-Fi", "via mobile data", "via VPN", "None". */
internal fun internetSource(net: Internet): String = when {
    !net.usable(allowMobile = true) -> "None"
    net.vpn -> "via VPN"
    net.wifi -> "via Wi-Fi"
    net.mobile -> "via mobile data"
    else -> "Connected"
}

/** A name and, under it, a smaller status line in [subtitleColor], each kept to one line. */
@Composable
private fun TwoLines(title: String, subtitle: String?, subtitleColor: Color, trailing: (@Composable () -> Unit)? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                trailing?.invoke()
            }
        }
    }
}
