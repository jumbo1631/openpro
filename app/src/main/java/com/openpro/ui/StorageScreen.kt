package com.openpro.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openpro.storage.SaveTarget
import com.openpro.storage.Storage
import kotlinx.coroutines.launch

/**
 * Storage: where downloads go (one choice, named by device and folder), how they're arranged in
 * folders, how much each device holds, thumbnails, and (under Advanced) where working files go.
 */
@Composable
fun StorageScreen(vm: AppViewModel, onBack: () -> Unit) {
    val storage = vm.storage
    val volumes by storage.volumes.volumes.collectAsState()
    val workChoice by storage.workChoice.collectAsState()
    val target by storage.saveTarget.collectAsState()
    var usage by remember { mutableStateOf<List<Storage.Usage>>(emptyList()) }
    var folderUsage by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var advanced by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            storage.useFolder(uri)
            refresh++
        }
    }
    LaunchedEffect(volumes, target, refresh) {
        usage = storage.usage()
        folderUsage = storage.folderUsage()
    }

    Screen("Storage", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Downloads go to", style = MaterialTheme.typography.titleMedium)
            volumes.filter { it.hasGallery }.forEach { v ->
                val t = SaveTarget.Gallery(v.id)
                Option(storage.deviceLabel(v.id), "${Storage.GALLERY_PATH} (photos in ${Storage.PHOTO_PATH})", target == t) { storage.setSaveTarget(t) }
            }
            (target as? SaveTarget.Gallery)?.takeIf { g -> volumes.none { it.id == g.volumeId && it.hasGallery } }?.let { g ->
                Option(storage.deviceLabel(g.volumeId), "${Storage.GALLERY_PATH} · not inserted", true) {}
            }
            storage.pickedFolder()?.let { f ->
                // A folder from imported settings (or whose access was revoked) can't be used until it's
                // picked again: choosing it opens Android's folder picker on it rather than just selecting it.
                val access = remember(f, refresh) { storage.hasAccess(f) }
                Option(storage.deviceLabel(storage.folderVolumeId(f)), storage.folderPath(f), target == f) {
                    if (access) storage.setSaveTarget(f) else pickFolder.launch(storage.pickerStart(f))
                }
                if (!access) {
                    Text(
                        "No access to \"${f.label}\" yet. Tap it, then tap Use this folder and Allow in Android's folder picker.",
                        Modifier.padding(start = 48.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            androidx.compose.material3.TextButton(onClick = { pickFolder.launch(null) }) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.CreateNewFolder, null)
                Text("Choose a folder…", Modifier.padding(start = 8.dp))
            }
            Text(
                "Changing this moves nothing: what you've downloaded stays where it is, and downloads already in the " +
                    "batch still go where they were queued to. OpenPro lists every copy wherever it is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val layout by storage.downloadLayout.collectAsState()
            FolderLayout(
                "Folders inside it",
                storage.saveTargetLabel(target),
                com.openpro.media.PathLayout.downloadPresets, layout,
            ) { storage.setDownloadLayout(it) }

            Text("Space", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            usage.forEach { VolumeCard(it) }
            volumes.filter { !it.mounted }.forEach { v ->
                Text("${v.label}: not inserted", style = MaterialTheme.typography.bodySmall)
            }
            folderUsage?.let { (label, bytes) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Text("Downloaded there: ${formatBytes(bytes)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(
                "If you remove a card or drive, copies on it show as not inserted, and jobs that need it wait until " +
                    "it's back. Nothing is deleted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ThumbnailSettings(storage)

            Row(
                Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.Icon(
                    if (advanced) androidx.compose.material.icons.Icons.Filled.ExpandLess else androidx.compose.material.icons.Icons.Filled.ExpandMore,
                    if (advanced) "Hide advanced" else "Show advanced",
                )
            }
            if (advanced) {
                Text("Working files", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Downloads in progress, files waiting to upload, and stabilizing. Normally on the same device as " +
                        "downloads, so a finished download is moved into place rather than copied.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Option("Same as downloads", storage.deviceLabel(storage.workVolumeId), workChoice == null) {
                    storage.setWorkVolume(null)
                    refresh++
                }
                volumes.filter { it.canHoldWork }.forEach { v ->
                    Option(storage.deviceLabel(v.id), v.root?.let { "${formatBytes(it.usableSpace)} free" }, workChoice == v.id) {
                        storage.setWorkVolume(v.id)
                        refresh++
                    }
                }
                workChoice?.takeIf { id -> volumes.none { it.id == id && it.canHoldWork } }?.let { id ->
                    Option(storage.deviceLabel(id), "not inserted", true) {}
                }
            }
        }
    }
}

/** One storage device: a capacity bar split into OpenPro's parts, other data, and free space. */
@Composable
private fun VolumeCard(u: Storage.Usage) {
    val colors = MaterialTheme.colorScheme
    val parts = listOf(
        Triple("Gallery (Movies/OpenPro)", u.gallery, colors.primary),
        Triple("Downloads in progress", u.originals, colors.tertiary),
        Triple("Staging and stabilizing", u.working, colors.secondary),
        Triple("Previews and thumbnails", u.cache, colors.outline),
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(u.volume.label + if (u.volume.removable) " (removable)" else "", style = MaterialTheme.typography.titleSmall)
            Text(
                "${formatBytes(u.free)} free of ${formatBytes(u.total)} · OpenPro ${formatBytes(u.openPro)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (u.total > 0) {
                val other = (u.total - u.free - u.openPro).coerceAtLeast(0)
                Row(Modifier.fillMaxWidth().height(10.dp).background(Color.DarkGray, RoundedCornerShape(5.dp))) {
                    parts.forEach { (_, bytes, color) -> if (bytes > 0) Bar(bytes.toFloat() / u.total, color) }
                    if (other > 0) Bar(other.toFloat() / u.total, Color.Gray)
                    // Weights are relative, so free space needs its own (empty) segment.
                    Bar(u.free.toFloat() / u.total, Color.Transparent)
                }
            }
            parts.filter { it.second > 0 }.forEach { (label, bytes, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(end = 6.dp).height(8.dp).width(10.dp).background(color))
                    Text("$label: ${formatBytes(bytes)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Bar(fraction: Float, color: Color) {
    if (fraction <= 0f) return
    Box(Modifier.weight(fraction.coerceAtLeast(0.005f)).fillMaxHeight().background(color))
}

/**
 * Gallery thumbnails: how sharp (or none), and which qualities are kept on the phone. Items show
 * without thumbnails too, so both are the user's trade of looks against storage and camera traffic.
 */
@Composable
private fun ThumbnailSettings(storage: com.openpro.storage.Storage) {
    val quality by storage.thumbQuality.collectAsState()
    val kept by storage.keptThumbs.collectAsState()
    Text("Gallery thumbnails", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        "The pictures on gallery items. Items work without them: a placeholder of the same size takes their place.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Quality", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    listOf(
        Triple(null, "None", "Placeholders only. Nothing is fetched from the camera for them or kept."),
        Triple(com.openpro.media.ThumbQuality.Low, "Low", "Small and quick: mostly the camera's own thumbnail."),
        Triple(com.openpro.media.ThumbQuality.High, "High", "Sharper, especially on large grid items: a frame from the clip's low-res copy, so slower to make."),
    ).forEach { (q, label, description) ->
        Row(Modifier.fillMaxWidth().clickable { storage.setThumbQuality(q) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = quality == q, onClick = { storage.setThumbQuality(q) })
            Column {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Text("Keep on this phone", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    Text(
        "Kept thumbnails show straight away, even without the camera. Ones not kept are fetched from the camera " +
            "again each time you browse. Unticking deletes the ones kept.",
        style = MaterialTheme.typography.bodySmall,
    )
    com.openpro.media.ThumbQuality.entries.forEach { q ->
        val keep = q in kept
        val bytes = remember(keep) { storage.thumbBytes(q) }
        Row(Modifier.fillMaxWidth().clickable { storage.setKeepThumbs(q, !keep) }, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = keep, onCheckedChange = { storage.setKeepThumbs(q, it) })
            Column {
                Text("${q.label} thumbnails", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (keep) "${formatBytes(bytes)} kept (about ${if (q == com.openpro.media.ThumbQuality.Low) "10–20" else "40–60"} KB per item)" else "Not kept",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Option(label: String, detail: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
