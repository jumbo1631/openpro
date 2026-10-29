package com.openpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openpro.camera.CameraModels
import com.openpro.camera.CameraState
import com.openpro.camera.SavedCamera

/**
 * Home: the camera card (the camera in use, with a dropdown to switch to another or add one, then
 * browsing and the remote), then everything else.
 */
@Composable
fun ConnectScreen(
    vm: AppViewModel, onBrowse: () -> Unit, onModels: () -> Unit, onStorage: () -> Unit, onNetwork: () -> Unit, onBatch: () -> Unit,
    onServers: () -> Unit, onRemote: () -> Unit, onAbout: () -> Unit,
) {
    val state by vm.cameraState.collectAsState()
    val bluetooth by vm.camera.ble.connected.collectAsState()
    val cameras by vm.camera.saved.cameras.collectAsState()
    val address by vm.camera.address.collectAsState()
    val justAdded by vm.camera.justAdded.collectAsState()
    val activeJobs by vm.activeJobCount.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SavedCamera?>(null) }
    var forgetting by remember { mutableStateOf<SavedCamera?>(null) }

    val model = (state as? CameraState.Ready)?.model ?: (state as? CameraState.Online)?.model
    val connected = state is CameraState.Ready || state is CameraState.Online
    val usable = connected && model?.support?.usable == true
    val hint = when {
        usable -> null
        connected -> "Not supported on this camera"
        else -> "Connect a camera first"
    }
    // The camera in use: the one connected (or connecting), else the one used last.
    val current = cameras.firstOrNull { it.address == address } ?: vm.camera.saved.last

    Screen("OpenPro") { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (current == null) {
                        Button(onClick = { adding = true }, Modifier.fillMaxWidth().height(56.dp)) {
                            Icon(Icons.Filled.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add device", style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        DeviceRow(
                            current, state.takeIf { current.address == address }, bluetooth && current.address == address, cameras,
                            onPick = { cam -> if (cam.address != address || !connected) vm.switchCamera(cam.address) },
                            onAdd = { adding = true },
                            onConnect = { vm.reconnect(current.address) },
                            onCancel = { vm.camera.cancelConnecting() },
                            onDisconnect = { vm.disconnect() },
                            onRename = { renaming = current },
                            onForget = { forgetting = current },
                        )
                    }
                    // A camera that isn't in the list: being added, or refused as unsupported.
                    if (cameras.none { it.address == address }) StatusLine(state, onCancel = { vm.camera.cancelConnecting() })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Works without a camera too: what it had last time, and what's saved.
                        Tile(
                            HomeTile("Browse media", Icons.Filled.PhotoLibrary, if (usable) "On the camera and saved" else "Last listed and saved") {
                                if (usable) vm.browse()
                                onBrowse()
                            },
                            Modifier.weight(1f), height = 112.dp,
                        )
                        Tile(
                            HomeTile("Remote control", Icons.Filled.SettingsRemote, hint ?: "And live preview", enabled = hint == null, onClick = onRemote),
                            Modifier.weight(1f), height = 112.dp,
                        )
                    }
                    if (usable) VpnNote(vm)
                }
            }
            HomeGrid(
                listOf(
                    HomeTile("Batch", Icons.Filled.SwapVert, if (activeJobs > 0) "$activeJobs active" else "Downloads and uploads", badge = activeJobs, onClick = onBatch),
                    HomeTile("Servers", Icons.Filled.Cloud, "Upload destinations", onClick = onServers),
                    HomeTile("Storage", Icons.Filled.Storage, "Space and save locations", onClick = onStorage),
                    HomeTile("Network", Icons.Filled.Wifi, "Camera Link, mobile data", onClick = onNetwork),
                    HomeTile("Camera models", Icons.Filled.PhotoCamera, "What's supported", accent = false, onClick = onModels),
                    HomeTile("About", Icons.Filled.Info, "Version, feedback, GitHub", accent = false, onClick = onAbout),
                ),
            )
        }
    }

    if (adding) AddDeviceSheet(vm, onDismiss = { adding = false })
    renaming?.let { cam ->
        RenameDialog(vm, cam, "Rename camera", onDone = { renaming = null })
    }
    // A camera added for the first time gets a name straight away (its model's, unless changed here).
    justAdded?.let { added ->
        cameras.firstOrNull { it.address == added }?.let { cam ->
            RenameDialog(vm, cam, "Name your camera", onDone = { vm.camera.namePrompted() })
        }
    }
    forgetting?.let { cam ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${cam.name}?") },
            text = {
                Text(
                    "OpenPro stops listing it. Its downloads and uploads stay where they are. To use it again, add it again. " +
                        "Android keeps the Bluetooth pairing until you remove it in Bluetooth settings.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.camera.forget(cam.address); forgetting = null }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The camera in use: its name (tap for a dropdown of the other cameras, and Add device), its
 * Bluetooth connection (Connect, or Cancel while connecting), and a menu to disconnect, rename or
 * forget it. Only Bluetooth here: the camera's Wi-Fi is the connection bar's, at the top.
 */
@Composable
private fun DeviceRow(
    cam: SavedCamera, state: CameraState?, bluetooth: Boolean, cameras: List<SavedCamera>, onPick: (SavedCamera) -> Unit, onAdd: () -> Unit,
    onConnect: () -> Unit, onCancel: () -> Unit, onDisconnect: () -> Unit, onRename: () -> Unit, onForget: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    // Joining the camera's Wi-Fi is Working too, but Bluetooth is connected by then.
    val connected = state is CameraState.Ready || state is CameraState.Online || state is CameraState.Working && bluetooth
    val busy = state is CameraState.Working && !bluetooth
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.PhotoCamera, null, Modifier.padding(end = 12.dp),
            tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box {
                Row(Modifier.clickable { picking = true }, verticalAlignment = Alignment.CenterVertically) {
                    Text(cam.name, style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Filled.ArrowDropDown, "Switch camera or add one")
                }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    cameras.forEach { other ->
                        DropdownMenuItem(
                            text = { Text(other.name) },
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) },
                            trailingIcon = { if (other.address == cam.address) Icon(Icons.Filled.Check, "In use") },
                            onClick = { picking = false; onPick(other) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Add device") },
                        leadingIcon = { Icon(Icons.Filled.Add, null) },
                        onClick = { picking = false; onAdd() },
                    )
                }
            }
            when {
                busy -> {
                    BluetoothStatus(Icons.Filled.Bluetooth, "Bluetooth: connecting…", MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
                }
                connected -> BluetoothStatus(Icons.Filled.BluetoothConnected, "Bluetooth: connected", MaterialTheme.colorScheme.primary)
                state is CameraState.Failed -> ShortMessage(state.message, color = MaterialTheme.colorScheme.error, title = "Couldn't connect")
                else -> BluetoothStatus(Icons.Filled.BluetoothDisabled, "Bluetooth: not connected", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (connected && cam.model?.support?.usable != true) {
                Text(CameraModels.unsupportedMessage(cam.modelId ?: -1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (!connected && !busy) TextButton(onClick = onConnect) { Text("Connect") }
        if (busy) TextButton(onClick = onCancel) { Text("Cancel") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More for ${cam.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (connected) DropdownMenuItem(text = { Text("Disconnect Bluetooth and put to sleep") }, onClick = { menu = false; onDisconnect() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Forget") }, onClick = { menu = false; onForget() })
            }
        }
    }
}

/** The camera row's Bluetooth line: a Bluetooth icon and "Bluetooth: connected" (or not, or connecting). */
@Composable
private fun BluetoothStatus(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.padding(end = 4.dp).size(14.dp), tint = color)
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** What's happening with a camera that isn't in the list yet (or was refused). */
@Composable
private fun StatusLine(state: CameraState, onCancel: () -> Unit) {
    when (state) {
        is CameraState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
            Text(state.message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        is CameraState.Failed -> ShortMessage(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, title = "Couldn't connect")
        else -> {}
    }
}

@Composable
private fun VpnNote(vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (!com.openpro.camera.wifi.CameraReachability.vpnActive(context)) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (vm.camera.link.installed) {
                "A VPN is active. Camera traffic will route through Camera Link."
            } else {
                "A VPN is active. Consider installing Camera Link (ⓘ for more info)."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        InfoButton(Explainers.cameraConnection(vm))
    }
}

/**
 * Adding a camera: scans while open, with how to put the camera in pairing mode. Cameras whose
 * advertisement says OpenPro doesn't support them are shown but can't be picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDeviceSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val found by vm.found.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val added by vm.camera.saved.cameras.collectAsState()
    val bluetoothOn by vm.camera.ble.enabled.collectAsState()
    // Searching needs Bluetooth: ask once on opening if it's off, and search as soon as it's on.
    LaunchedEffect(Unit) { if (!bluetoothOn) vm.askForBluetooth() }
    LaunchedEffect(bluetoothOn) { if (bluetoothOn) vm.startScan() else vm.stopScan() }
    DisposableEffect(Unit) { onDispose { vm.stopScan() } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add device", style = MaterialTheme.typography.titleLarge)
            Text(
                "On the camera, open Preferences › Wireless Connections › Connect Device. It shows up below: tap it, " +
                    "then accept the pairing request.",
                style = MaterialTheme.typography.bodyMedium,
            )
            // One height whichever is showing, so the sheet doesn't jump between them.
            Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                when {
                    !bluetoothOn -> Button(onClick = { vm.askForBluetooth() }, Modifier.fillMaxWidth()) { Text("Turn on Bluetooth") }
                    scanning -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> OutlinedButton(onClick = { vm.startScan() }, Modifier.fillMaxWidth()) { Text("Search again") }
                }
            }
            if (found.isEmpty() && scanning) {
                Text("Looking for cameras…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            found.forEach { cam ->
                val model = cam.modelId?.let { CameraModels.byModelId(it) }
                val unsupported = cam.modelId != null && !CameraModels.isUsable(cam.modelId)
                val already = added.firstOrNull { it.address == cam.device.address }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (unsupported) Modifier else Modifier.clickable { vm.connect(cam); onDismiss() })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(already?.name ?: cam.name, style = MaterialTheme.typography.titleMedium)
                        val what = model?.displayName ?: cam.modelId?.let { "Unknown model (ID $it)" }
                        Text(
                            listOfNotNull(what, cam.name.takeIf { already != null && it != already.name }, "${cam.rssi} dBm").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (unsupported) {
                            Text("Not supported by OpenPro", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (!unsupported) Text(if (already != null) "Connect" else "Add", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * Naming a camera: its model's name to start with. When the download layout has a folder per
 * camera, renaming asks whether new downloads should follow the new name or stay in its folder.
 */
@Composable
private fun RenameDialog(vm: AppViewModel, cam: SavedCamera, title: String, onDone: () -> Unit) {
    var text by remember(cam.address) { mutableStateOf(cam.name) }
    var moveFolder by remember(cam.address) { mutableStateOf(true) }
    val perCamera = com.openpro.media.PathLayout.usesCamera(vm.storage.downloadLayout.value)
    val changesFolder = perCamera && text.trim().ifEmpty { cam.defaultName } != cam.folderName
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (text.trim() != cam.defaultName) {
                    TextButton(onClick = { text = cam.defaultName }) { Text("Use \"${cam.defaultName}\"") }
                }
                // Names (and so download folders) should tell cameras apart.
                val wanted = text.trim().ifEmpty { cam.defaultName }
                vm.camera.saved.cameras.value.firstOrNull { other ->
                    other.address != cam.address && (other.name.equals(wanted, ignoreCase = true) || other.folderName.equals(wanted, ignoreCase = true))
                }?.let { other ->
                    Text(
                        "Another camera (${other.name}) already uses this name, so their downloads would share a folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (changesFolder) {
                    Row(Modifier.fillMaxWidth().clickable { moveFolder = !moveFolder }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = moveFolder, onCheckedChange = { moveFolder = it })
                        Text(
                            "New downloads go in a folder with the new name (otherwise they stay in \"${cam.folderName}\"). " +
                                "Files already downloaded aren't moved.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // The model's name is stored as "no name of its own", so it follows the model.
                vm.camera.saved.rename(cam.address, if (text.trim() == cam.defaultName) "" else text, renameFolder = !changesFolder || moveFolder)
                onDone()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

private class HomeTile(
    val title: String,
    val icon: ImageVector,
    val subtitle: String,
    val badge: Int = 0,
    val enabled: Boolean = true,
    /** Off for reference pages (models, about): their icon is grey rather than the theme colour. */
    val accent: Boolean = true,
    val onClick: () -> Unit,
)

/** The home screen's other destinations as large tiles, two per row. */
@Composable
private fun HomeGrid(tiles: List<HomeTile>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { Tile(it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Tile(tile: HomeTile, modifier: Modifier, height: Dp = 136.dp) {
    ElevatedCard(onClick = tile.onClick, enabled = tile.enabled, modifier = modifier.height(height)) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BadgedBox(badge = { if (tile.badge > 0) Badge { Text("${tile.badge}") } }) {
                Icon(
                    tile.icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = when {
                        !tile.enabled -> LocalContentColor.current
                        tile.accent -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(tile.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                tile.subtitle,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = if (tile.enabled) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current,
            )
        }
    }
}
