package com.openpro.ui

import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openpro.camera.CameraState
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.openpro.camera.remote.OpenGoPro
import com.openpro.camera.remote.PreviewDecoder
import com.openpro.camera.remote.RemoteState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Remote control: live preview, shutter, modes and presets, settings, and camera actions. Control
 * runs over Bluetooth (works with the camera's WiFi off); only the live preview needs WiFi.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(vm: AppViewModel, onBack: () -> Unit) {
    val cameraState by vm.cameraState.collectAsState()
    val rs by vm.control.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The Bluetooth link is what remote control needs. Camera states like "Turning on camera
    // WiFi…" (while the preview starts) still have it, so they must not count as disconnected.
    val connected by vm.camera.ble.connected.collectAsState()
    var confirmSleep by remember { mutableStateOf(false) }

    DisposableEffect(connected) {
        if (connected) vm.control.start()
        onDispose { vm.control.stop() }
    }
    // The preview stops only when leaving the page, never because the camera's state changed.
    DisposableEffect(Unit) { onDispose { vm.stopPreview() } }
    HoldCameraWhileVisible(vm)

    fun act(what: suspend () -> Unit) = scope.launch {
        try {
            what()
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
        }
    }

    Screen("Remote", onBack = onBack) { padding ->
        if (!connected) {
            Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ShortMessage(
                    when (val s = cameraState) {
                        is CameraState.Working -> s.message
                        is CameraState.Failed -> "Lost the connection to the camera: ${s.message}"
                        else -> "The camera isn't connected."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    title = "Camera connection",
                )
                val last = vm.camera.saved.last
                if (cameraState !is CameraState.Working && last != null) {
                    Button(onClick = { vm.reconnectLast() }) { Text("Reconnect to ${last.name}") }
                }
            }
            return@Screen
        }
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PreviewBox(vm, rs)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusRow(rs)
                ShutterRow(rs, onShutter = { act { vm.control.shutter(!rs.recording) } }, onHilight = { act { vm.control.hilight() } })

                Text("Mode", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenGoPro.GROUPS.forEach { (id, label) ->
                        FilterChip(
                            selected = rs.presetGroup == id,
                            onClick = { act { vm.control.loadPresetGroup(id) } },
                            enabled = !rs.recording,
                            label = { Text(label) },
                        )
                    }
                }
                val groupPresets = rs.presets?.firstOrNull { it.id == rs.presetGroup }?.presets.orEmpty()
                if (groupPresets.isNotEmpty()) {
                    Text("Presets", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        groupPresets.forEach { p ->
                            FilterChip(
                                selected = rs.activePreset == p.id,
                                onClick = { act { vm.control.loadPreset(p.id) } },
                                enabled = !rs.recording,
                                label = { Text(p.label) },
                            )
                        }
                    }
                }

                SettingsSection(rs, enabled = !rs.recording) { id, value -> act { vm.control.setSetting(id, value) } }

                HorizontalDivider()
                Text("Camera", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        act {
                            vm.control.syncTime()
                            Toast.makeText(context, "Camera clock set from the phone", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Sync date and time") }
                    if (cameraState is CameraState.Online) {
                        OutlinedButton(onClick = { vm.stopPreview(); vm.goOffline() }) { Text("Turn camera Wi-Fi off") }
                    }
                    OutlinedButton(onClick = { act { vm.control.refreshAll() } }) { Text("Refresh") }
                    OutlinedButton(onClick = { confirmSleep = true }) { Text("Put camera to sleep") }
                }
                val notAnswered = rs.unsupported + if (rs.presets?.isEmpty() == true) setOf("presets") else emptySet()
                if (notAnswered.isNotEmpty()) {
                    Text(
                        "The camera didn't answer OpenPro's requests for: ${notAnswered.joinToString()}. Those controls are hidden; " +
                            "this camera may not support them.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    if (rs.pushing) "Live: the camera sends updates as they happen." else "Updating every 2 seconds.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (confirmSleep) {
        AlertDialog(
            onDismissRequest = { confirmSleep = false },
            title = { Text("Put the camera to sleep?") },
            text = { Text("The camera powers down and OpenPro disconnects. Wake it with its button, then reconnect.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSleep = false
                    vm.stopPreview()
                    vm.disconnect()
                    onBack()
                }) { Text("Sleep") }
            },
            dismissButton = { TextButton(onClick = { confirmSleep = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PreviewBox(vm: AppViewModel, rs: RemoteState) {
    val port by vm.previewPort.collectAsState()
    val status by vm.previewStatus.collectAsState()
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
        val p = port
        if (p != null) {
            LivePlayer(p, vm::previewPackets)
            // A dark, see-through backing keeps it readable over a bright picture.
            TextButton(
                onClick = { vm.stopPreview() },
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                ),
            ) { Text("Stop preview") }
        } else {
            Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                status?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { vm.startPreview() }, enabled = status == null || port == null) { Text("Start live preview") }
                Text("Needs the camera's Wi-Fi; OpenPro turns it on.", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (rs.recording) RecordingBadge(rs.statuses[OpenGoPro.ST_VIDEO_PROGRESS] ?: 0, Modifier.align(Alignment.TopStart).padding(12.dp))
    }
}

/** While recording: a red dot that pulses, and the time recorded so far, shadowed to read over any picture. */
@Composable
private fun RecordingBadge(seconds: Long, modifier: Modifier) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(700),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "dot",
    )
    val shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 8f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).graphicsLayer { this.alpha = alpha }.background(Color(0xFFFF3B30), CircleShape))
        Text(
            formatDuration(seconds),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(shadow = shadow),
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Shows the camera's live preview arriving on a loopback UDP port, and says what's happening
 * instead of showing a silent black box: waiting for video, how much data is arriving, or an error.
 */
@Composable
private fun LivePlayer(port: Int, packets: () -> Long) {
    var picture by remember(port) { mutableStateOf(false) }
    var error by remember(port) { mutableStateOf<String?>(null) }
    var received by remember(port) { mutableStateOf(-1L) }
    var waitedSec by remember(port) { mutableStateOf(0) }
    var aspect by remember(port) { mutableStateOf(4f / 3f) }
    val listener = remember(port) {
        object : PreviewDecoder.Listener {
            override fun onFirstFrame() {
                picture = true
            }

            override fun onVideoSize(width: Int, height: Int) {
                if (width > 0 && height > 0) aspect = width.toFloat() / height
            }

            override fun onError(message: String) {
                error = message
            }
        }
    }
    LaunchedEffect(port) {
        while (!picture && error == null) {
            delay(1_000)
            waitedSec++
            received = packets()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        key(port) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            var decoder: PreviewDecoder? = null

                            override fun surfaceCreated(holder: SurfaceHolder) {
                                decoder = PreviewDecoder(port, holder.surface, listener).start()
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                decoder?.stop()
                                decoder = null
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxHeight().aspectRatio(aspect),
            )
        }
    }
    if (!picture) {
        val text = error ?: when {
            received > 0 && waitedSec >= 20 ->
                "Video is arriving from the camera ($received packets), but it can't be shown. Stop and start the preview to try again."
            received > 0 -> "Receiving video from the camera ($received packets), waiting for a picture…"
            received == 0L && waitedSec >= 5 ->
                "The camera hasn't sent any video yet. It may not support the live preview over Wi-Fi, or its Wi-Fi dropped."
            else -> "Waiting for video from the camera…"
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusRow(rs: RemoteState) {
    val s = rs.statuses
    val items = buildList {
        s[OpenGoPro.ST_BATTERY_PERCENT]?.let { add("Battery $it%") }
            ?: s[OpenGoPro.ST_BATTERY_BARS]?.let { add(if (it == 4L) "Battery charging" else "Battery ${it}/3") }
        s[OpenGoPro.ST_VIDEO_REMAINING]?.takeIf { it > 0 }?.let { add("${formatDuration(it)} of video left") }
        s[OpenGoPro.ST_SD_REMAINING_KB]?.takeIf { it > 0 }?.let { add("${formatBytes(it * 1024)} free on SD") }
        when (s[OpenGoPro.ST_SD_STATUS]) {
            1L -> add("SD card full")
            2L -> add("No SD card")
        }
        if (s[OpenGoPro.ST_HOT] == 1L) add("Camera is hot")
        if (s[OpenGoPro.ST_COLD] == 1L) add("Camera is too cold")
        if (rs.busy) add("Busy")
    }
    if (items.isEmpty()) {
        Text("Reading the camera's status…", style = MaterialTheme.typography.bodySmall)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { item ->
            val warn = item.startsWith("Camera is") || item.startsWith("No SD") || item.endsWith("full")
            Text(item, style = MaterialTheme.typography.bodyMedium, color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ShutterRow(rs: RemoteState, onShutter: () -> Unit, onHilight: () -> Unit) {
    val photo = rs.presetGroup == OpenGoPro.GROUP_PHOTO
    // Equal space either side keeps the shutter in the middle; HiLight sits in the right-hand space.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(84.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                .background(if (rs.recording) Color(0xFFD32F2F) else Color(0xFFE53935).copy(alpha = 0.85f), CircleShape)
                .clickable(onClick = onShutter),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    rs.recording -> "Stop"
                    photo -> "Photo"
                    else -> "Record"
                },
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (rs.recording) {
                OutlinedButton(onClick = onHilight, Modifier.padding(start = 16.dp)) { Text("HiLight") }
            }
        }
    }
}

@Composable
private fun SettingsSection(rs: RemoteState, enabled: Boolean, onSet: (Int, Long) -> Unit) {
    val shown = OpenGoPro.SETTINGS.keys.filter { it in rs.settings }
    if (shown.isEmpty()) return
    Text("Settings", style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            shown.forEach { id ->
                val current = rs.settings[id]!!
                val options = rs.capabilities[id].orEmpty().ifEmpty { listOf(current) }
                var open by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = enabled && options.size > 1) { open = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(OpenGoPro.SETTINGS[id]!!.first, Modifier.weight(1f))
                        Text(
                            OpenGoPro.settingValueLabel(id, current),
                            color = if (options.size > 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        options.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(OpenGoPro.settingValueLabel(id, v) + if (v == current) "  ✓" else "") },
                                onClick = { open = false; if (v != current) onSet(id, v) },
                            )
                        }
                    }
                }
            }
        }
    }
    Text("Only values the camera currently allows are listed; some depend on others (e.g. frame rate on resolution).", style = MaterialTheme.typography.bodySmall)
}
