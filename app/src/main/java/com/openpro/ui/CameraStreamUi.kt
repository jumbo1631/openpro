package com.openpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openpro.camera.http.CameraStreams

/**
 * Takes the camera's one stream ([CameraStreams]) for this screen while [wanted], doing [doing]
 * ("a preview is playing"), and gives it back when no longer wanted or the screen goes. Returns
 * whether this screen has it; if someone else does, it's taken as soon as they let go.
 */
@Composable
fun rememberCameraStream(doing: String, wanted: Boolean): Boolean {
    var mine by remember { mutableStateOf<CameraStreams.Holder?>(null) }
    val holder by CameraStreams.holder.collectAsState()
    LaunchedEffect(wanted, holder) {
        if (wanted && mine == null && holder == null) mine = CameraStreams.tryAcquire(CameraStreams.Who.Viewer, doing)
        if (!wanted) mine?.let { CameraStreams.release(it); mine = null }
    }
    DisposableEffect(Unit) { onDispose { mine?.let(CameraStreams::release) } }
    return mine != null && holder?.id == mine?.id
}

/**
 * In place of a preview or photo while the camera is busy with something else: what it's doing,
 * and (if it's the batch) Pause batch, which frees it.
 */
@Composable
fun CameraBusy(vm: AppViewModel, modifier: Modifier = Modifier, color: Color = Color.White) {
    val holder by CameraStreams.holder.collectAsState()
    val batch = holder?.who == CameraStreams.Who.Batch
    Column(modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "The camera can stream one thing at a time, and ${holder?.doing ?: "it's busy"}.",
            color = color, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
        )
        if (batch) OutlinedButton(onClick = { vm.batch.setPaused(true) }) { Text("Pause batch") }
    }
}
