package com.openpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openpro.batch.JobVariant
import com.openpro.batch.UploadOptions
import com.openpro.media.CameraMedia
import com.openpro.quik.StabilizationChoice

/**
 * One place to decide what happens to selected clips: download them, upload them, or both, and
 * the button says which. Where downloads and uploads go is set once, under Storage and Servers. Deleting afterwards is opt-in, and deleting from the camera is confirmed.
 * The gallery's toolbar has one-tap versions of the common cases; this is its "Custom…".
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SaveSheet(vm: AppViewModel, items: List<CameraMedia>, onDismiss: () -> Unit, onServers: () -> Unit) {
    val choices = vm.saveChoices
    val index by vm.copyIndex.collectAsState()
    val uploadServer by vm.servers.defaultId.collectAsState()
    var variant by remember { mutableStateOf(JobVariant.Original) }
    var stabilization by remember { mutableStateOf(StabilizationChoice.Suggested) }
    val videos = items.count { it.isVideo }
    val withPreview = items.filter { it.hasLrv }
    var toPhone by remember { mutableStateOf(choices.toPhone) }
    var toServer by remember { mutableStateOf(choices.toServer) }
    var deletePhone by remember { mutableStateOf(choices.deletePhoneCopy) }
    var deleteCamera by remember { mutableStateOf(false) }
    var warnCamera by remember { mutableStateOf(false) }
    var needServer by remember { mutableStateOf(false) }

    val copies = items.map { vm.copies(it, index) }
    val onPhone = items.zip(copies).filter { (_, c) -> c.onPhone || c.onCard }.map { it.first }
    val n = items.size
    val noun = when (videos) {
        n -> "clip"
        0 -> "photo"
        else -> "item"
    }
    val clips = if (n == 1) "1 $noun" else "$n ${noun}s"
    // Deleting the phone's copy only makes sense when the phone isn't a destination.
    val canDeletePhone = toServer && !toPhone && onPhone.isNotEmpty()
    // How many already have the chosen version: on the phone, and on the server uploads go to.
    fun versionOf(m: CameraMedia) = when {
        variant == JobVariant.Preview -> com.openpro.media.ClipVersion.Preview
        variant == JobVariant.Stabilized && m.isVideo -> com.openpro.media.ClipVersion.Stabilized(stabilization)
        else -> com.openpro.media.ClipVersion.Original
    }
    val downloaded = items.count { m -> index[m.id].orEmpty().any { it.isLocal && it.version == versionOf(m) } }
    val uploaded = items.count { m -> index[m.id].orEmpty().any { !it.isLocal && it.serverId == uploadServer && it.version == versionOf(m) } }
    val action = when {
        toPhone && toServer -> "Download & upload $n"
        toServer -> "Upload $n"
        toPhone -> "Download $n"
        else -> null
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Save $clips", style = MaterialTheme.typography.titleLarge)

            // Photos have only the one version, so with only photos there's nothing to choose.
            if (videos > 0) {
                Section("Version")
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = variant == JobVariant.Original, onClick = { variant = JobVariant.Original }, label = { Text("Original") })
                    if (withPreview.isNotEmpty()) {
                        FilterChip(selected = variant == JobVariant.Preview, onClick = { variant = JobVariant.Preview }, label = { Text("Preview") })
                    }
                    FilterChip(
                        selected = variant == JobVariant.Stabilized, onClick = { variant = JobVariant.Stabilized }, label = { Text("Stabilized") },
                        enabled = vm.quik.engineAvailable,
                    )
                }
                if (!vm.quik.engineAvailable) Hint(NO_ENGINE)
                when (variant) {
                    JobVariant.Stabilized -> {
                        // The kinds that fit at least one selected video: 360 kinds for 360 clips, the rest for flat ones.
                        val kinds = listOf(false, true).filter { is360 -> items.any { it.isVideo && (it.extension == "360") == is360 } }
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StabilizationChoice.entries.filter { c -> c != StabilizationChoice.Off && kinds.any(c::fits) }.forEach { c ->
                                FilterChip(selected = stabilization == c, onClick = { stabilization = c }, label = { Text(c.label) })
                            }
                        }
                        if (kinds.size == 2 && stabilization != StabilizationChoice.Suggested) {
                            Hint("360 and flat clips are stabilized differently: clips this choice doesn't fit get GoPro default.")
                        }
                    }
                    JobVariant.Preview -> Hint(
                        "The camera's low-res copy (${formatBytes(withPreview.sumOf { it.lrvSizeBytes })} in all): small and quick to " +
                            "share or edit. It can't be stabilized." +
                            (n - withPreview.size).let { if (it > 0) " $it without a preview will be left out." else "" },
                    )
                    JobVariant.Original -> {}
                }
                if (variant == JobVariant.Stabilized && videos < n) Hint("${n - videos} photo${if (n - videos == 1) " is" else "s are"} saved as they are.")
            }

            Section("Save")
            SaveRows(vm, n, toPhone, { toPhone = it }, toServer, { toServer = it }, downloaded, uploaded)

            Section("Afterwards")
            CheckRow(
                "Delete from this phone once uploaded",
                when {
                    !toServer -> "Only after an upload"
                    toPhone -> "Not while downloading them too"
                    onPhone.isEmpty() -> "None of these clips are on this phone"
                    else -> "${onPhone.size} on this phone, ${formatBytes(onPhone.sumOf { it.sizeBytes })}. " +
                        "Only once the server's copy is verified."
                },
                deletePhone && canDeletePhone,
                enabled = canDeletePhone,
            ) { deletePhone = it }
            val saving = toPhone || toServer
            // Saving only the preview never deletes the clip: the clip itself isn't saved anywhere.
            val canDeleteCamera = saving && variant != JobVariant.Preview
            CheckRow(
                "Delete from the camera once saved",
                when {
                    !saving -> "Choose Download or Upload first"
                    variant == JobVariant.Preview -> "Not when saving only the preview: the clip itself wouldn't be saved anywhere"
                    variant == JobVariant.Stabilized -> "Only the stabilized version is saved: the original will be gone"
                    toPhone && toServer -> "Once both copies are saved and checked"
                    toServer -> "Once the server's copy is checked"
                    else -> "Once the phone's copy is saved and checked"
                },
                deleteCamera && canDeleteCamera,
                enabled = canDeleteCamera,
                warning = true,
            ) { v ->
                if (v && !choices.cameraDeleteWarningSeen) warnCamera = true else deleteCamera = v
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = action != null,
                    onClick = {
                        choices.toPhone = toPhone
                        choices.toServer = toServer
                        if (canDeletePhone) choices.deletePhoneCopy = deletePhone
                        val queued = vm.save(
                            items,
                            variant,
                            if (variant == JobVariant.Stabilized) stabilization else null,
                            toPhone,
                            toServer,
                            UploadOptions(deletePhoneCopy = deletePhone && canDeletePhone, deleteFromCamera = deleteCamera && canDeleteCamera),
                        )
                        if (queued) onDismiss() else needServer = true
                    },
                ) { Text(action ?: "Choose Download or Upload") }
            }
        }
    }

    if (warnCamera) CameraDeleteWarning(
        onConfirm = { dontShowAgain ->
            if (dontShowAgain) choices.cameraDeleteWarningSeen = true
            deleteCamera = true
            warnCamera = false
        },
        onDismiss = { warnCamera = false },
    )
    if (needServer) ServerNeededDialog(vm, onSetUp = { needServer = false; onDismiss(); onServers() }, onDismiss = { needServer = false })
}

/** Explains deleting from the camera before the first time it's ticked. */
@Composable
private fun CameraDeleteWarning(onConfirm: (dontShowAgain: Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontShow by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, null) },
        title = { Text("Delete from the camera?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Each clip is deleted from the camera's SD card once it's saved where you chose and OpenPro has checked " +
                        "every copy: the phone's by its size, the server's by its size and, where the server reports one, its checksum.",
                )
                Text(
                    "Deleted clips can't be recovered from the camera. If you save only the stabilized version, the original " +
                        "is gone. A clip is kept on the camera if the server already had a file of that name that only matched " +
                        "by size, or if the camera's file has changed since.",
                )
                Row(
                    Modifier.fillMaxWidth().clickable { dontShow = !dontShow },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = dontShow, onCheckedChange = { dontShow = it })
                    Text("Don't show this again", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(dontShow) }) { Text("Delete once uploaded") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Why Stabilized can't be chosen in a build without GoPro's engine. */
internal const val NO_ENGINE = "Stabilizing needs GoPro's engine: add it under About › Add stabilization."

/**
 * Download (to wherever Storage says) and Upload (to the server selected under Servers), each
 * saying where, for [n] items of which [downloaded] and [uploaded] already have the chosen version
 * there. With no server set up yet, Upload still can be ticked: saving then asks to set one up.
 */
@Composable
internal fun SaveRows(
    vm: AppViewModel, n: Int, toPhone: Boolean, onToPhone: (Boolean) -> Unit, toServer: Boolean, onToServer: (Boolean) -> Unit,
    downloaded: Int, uploaded: Int,
) {
    val servers by vm.servers.servers.collectAsState()
    val target by vm.storage.saveTarget.collectAsState()
    vm.servers.defaultId.collectAsState().value // follows a change of upload server
    val server = vm.servers.defaultServer()
    fun already(count: Int, what: String) = when {
        count <= 0 -> null
        count == n -> if (n == 1) "Already $what" else "All already $what"
        else -> "$count of $n already $what"
    }
    // Where, then whether it's there already, a line each.
    CheckRow("Download", listOfNotNull("To ${vm.storage.saveTargetLabel(target)}", already(downloaded, "downloaded")).joinToString("\n"), toPhone) {
        onToPhone(it)
    }
    CheckRow(
        "Upload",
        if (server == null || servers.isEmpty()) "No server set up yet: you'll be asked to set one up"
        else listOfNotNull("To ${server.name} · ${server.folder}", already(uploaded, "uploaded")).joinToString("\n"),
        toServer,
    ) { onToServer(it) }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
internal fun CheckRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    warning: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                if (warning) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error.copy(alpha = alpha), modifier = Modifier.height(16.dp))
                }
            }
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)) }
        }
    }
}
