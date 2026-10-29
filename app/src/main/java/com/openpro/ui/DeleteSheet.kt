package com.openpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.openpro.media.CameraMedia
import com.openpro.media.ClipVersion

/** One copy of one clip that could be deleted. */
typealias DeleteItem = Pair<CameraMedia, ClipCopy>

/**
 * A choice in the delete sheet: a place (camera, this phone, a server), a version within it
 * (originals, previews, stabilized) or a stabilization method. Only groups with something in them
 * exist; ticking a group ticks everything under it.
 */
class DeleteGroup(
    val key: String, val label: String, val icon: ImageVector?, val items: List<DeleteItem>, val children: List<DeleteGroup>,
    /** For a group that ticks others without listing them as its children ("Delete all"). */
    private val covers: List<DeleteGroup>? = null,
) {
    val leaves: List<DeleteGroup> get() = covers ?: if (children.isEmpty()) listOf(this) else children.flatMap { it.leaves }
}

/** Groups every reachable copy of the selected clips by where it is, then by version and method. */
fun deleteGroups(items: List<DeleteItem>): List<DeleteGroup> = buildList {
    val reachable = items.filter { it.second.ref != null }
    reachable.filter { it.second.place is ClipCopy.Place.Camera }.takeIf { it.isNotEmpty() }?.let {
        add(DeleteGroup("camera", "From the camera", Icons.Filled.PhotoCamera, it, emptyList()))
    }
    reachable.filter { it.second.place is ClipCopy.Place.Phone }.takeIf { it.isNotEmpty() }?.let {
        add(DeleteGroup("phone", "On this phone", Icons.Filled.PhoneAndroid, it, versionGroups("phone", it)))
    }
    reachable.filter { it.second.place is ClipCopy.Place.Server }
        .groupBy { (it.second.place as ClipCopy.Place.Server).serverId }
        .forEach { (id, list) ->
            add(DeleteGroup("server:$id", "On ${list.first().second.where}", Icons.Filled.CloudDone, list, versionGroups("server:$id", list)))
        }
}

private fun versionGroups(prefix: String, items: List<DeleteItem>): List<DeleteGroup> = buildList {
    items.filter { it.second.version == ClipVersion.Original }.takeIf { it.isNotEmpty() }?.let {
        add(DeleteGroup("$prefix/original", originalsLabel(it), null, it, emptyList()))
    }
    items.filter { it.second.version == ClipVersion.Preview }.takeIf { it.isNotEmpty() }?.let {
        add(DeleteGroup("$prefix/preview", "Previews", null, it, emptyList()))
    }
    val stabilized = items.filter { it.second.version is ClipVersion.Stabilized }
    if (stabilized.isNotEmpty()) {
        val methods = stabilized.groupBy { (it.second.version as ClipVersion.Stabilized).method }
            .map { (method, list) ->
                DeleteGroup("$prefix/stabilized/${method.slug}", method.shortLabel, null, list, emptyList())
            }
        add(DeleteGroup("$prefix/stabilized", "Stabilized", null, stabilized, methods))
    }
}

/** "Originals", "Photos", or both, depending on what's in [items]. */
private fun originalsLabel(items: List<DeleteItem>): String {
    val photos = items.count { !it.first.isVideo }
    return when (photos) {
        0 -> "Originals"
        items.size -> "Photos"
        else -> "Originals and photos"
    }
}

/**
 * Deleting for the selected clips: every place and kind of copy they have, as checkboxes with how
 * many files each covers. Nothing is ticked to start with, and deleting asks for confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteSheet(vm: AppViewModel, media: List<CameraMedia>, onDismiss: () -> Unit) {
    val index by vm.copyIndex.collectAsState()
    val all = remember(media, index) {
        media.flatMap { m -> (listOf(vm.cameraCopy(m)) + vm.copiesOf(m, index)).map { m to it } }
    }
    val groups = remember(all) { deleteGroups(all) }
    var ticked by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val chosen = groups.flatMap { it.leaves }.filter { it.key in ticked }.flatMap { it.items }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Delete", style = MaterialTheme.typography.titleLarge)
            Text(
                "What to delete for the ${media.size} selected. Numbers are files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (groups.isEmpty()) Text("There's nothing OpenPro can reach to delete for these.", style = MaterialTheme.typography.bodyMedium)
            else GroupRows(DeleteGroup("all", "Delete all", null, groups.flatMap { it.items }, emptyList(), groups.flatMap { it.leaves }), 0, ticked) { ticked = it }
            if (groups.isNotEmpty()) androidx.compose.material3.HorizontalDivider()
            groups.forEach { group -> GroupRows(group, 0, ticked) { ticked = it } }

            Spacer(Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { confirm = true },
                    enabled = chosen.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(if (chosen.isEmpty()) "Delete" else "Delete ${files(chosen.size)}") }
            }
        }
    }
    if (confirm) {
        DeleteConfirm(chosen, all, onConfirm = { vm.deleteCopies(chosen); confirm = false; onDismiss() }, onDismiss = { confirm = false })
    }
}

@Composable
private fun GroupRows(group: DeleteGroup, depth: Int, ticked: Set<String>, onChange: (Set<String>) -> Unit) {
    val keys = group.leaves.map { it.key }
    val state = when {
        keys.all { it in ticked } -> ToggleableState.On
        keys.any { it in ticked } -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }
    val toggle = { onChange(if (state == ToggleableState.On) ticked - keys.toSet() else ticked + keys) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = toggle).padding(start = (depth * 28).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = state, onClick = toggle)
        group.icon?.let { Icon(it, null, Modifier.padding(end = 8.dp).size(20.dp)) }
        Text(
            "${group.label} (${group.items.size})",
            style = if (depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        val bytes = group.items.sumOf { it.second.sizeBytes ?: 0L }
        if (bytes > 0) Text(formatBytes(bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    group.children.forEach { GroupRows(it, depth + 1, ticked, onChange) }
}

/**
 * "Are you sure?" for deleting [chosen], with what goes from where. Only when clips are deleted
 * from the camera does it add a warning, and says how many would then have no original left
 * anywhere OpenPro knows of ([all] is every copy of the clips involved).
 */
@Composable
fun DeleteConfirm(chosen: List<DeleteItem>, all: List<DeleteItem>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val fromCamera = chosen.filter { it.second.ref is ClipCopy.Ref.Camera }
    val chosenSet = chosen.toSet()
    val lost = fromCamera.count { (media, _) ->
        all.none { item -> item.first.id == media.id && item !in chosenSet && item.second.version == ClipVersion.Original && item.second.place !is ClipCopy.Place.Camera }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${files(chosen.size)}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                summary(chosen).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text("This can't be undone.", style = MaterialTheme.typography.bodyMedium)
                if (chosen.any { it.second.place !is ClipCopy.Place.Phone }) {
                    Text(
                        "Deleting from the camera or a server needs its network, so it runs in the batch: first whatever the phone " +
                            "can reach now, then the rest after one Wi-Fi switch.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (fromCamera.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Deleting from the camera removes the original recording${if (fromCamera.size == 1) "" else "s"} " +
                                    "(and ${if (fromCamera.size == 1) "its" else "their"} previews) from its SD card.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                if (lost > 0) {
                                    (if (lost == fromCamera.size && lost == 1) "It" else "$lost of them") +
                                        " would then have no original anywhere OpenPro knows of: gone for good."
                                } else {
                                    "Each has an original kept elsewhere (on this phone or a server)."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "From the camera: 5 clips", "From this phone: 3 originals, 2 stabilized", one line per place. */
private fun summary(chosen: List<DeleteItem>): List<String> =
    chosen.groupBy { (_, c) ->
        when (c.place) {
            ClipCopy.Place.Camera -> "From the camera"
            is ClipCopy.Place.Phone -> "From this phone"
            is ClipCopy.Place.Server -> "From ${c.where}"
        }
    }.map { (place, list) ->
        if (place == "From the camera") return@map "$place: ${list.size} ${if (list.size == 1) "clip" else "clips"}"
        val kinds = list.groupBy { (media, c) ->
            when (c.version) {
                ClipVersion.Original -> if (media.isVideo) "original" else "photo"
                ClipVersion.Preview -> "preview"
                is ClipVersion.Stabilized -> "stabilized"
            }
        }.map { (kind, l) -> "${l.size} " + if (kind == "stabilized" || l.size == 1) kind else kind + "s" }
        "$place: ${kinds.joinToString(", ")}"
    }

private fun files(n: Int) = if (n == 1) "1 file" else "$n files"
