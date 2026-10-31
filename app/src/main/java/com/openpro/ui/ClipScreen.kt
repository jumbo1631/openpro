package com.openpro.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.openpro.batch.JobState
import com.openpro.batch.JobVariant
import com.openpro.batch.UploadOptions
import com.openpro.camera.CameraState
import com.openpro.camera.http.CameraApi
import com.openpro.copies.CopyEntity
import com.openpro.copies.CopyPlace
import com.openpro.media.CameraMedia
import com.openpro.media.ClipNames
import com.openpro.media.ClipVersion
import com.openpro.quik.StabilizationChoice
import kotlinx.coroutines.launch

/** What the clip page's player plays: the camera's preview (streamed), or a copy on the phone, by id. */
private const val CAMERA_PREVIEW = "camera"

/**
 * One clip: the player (or the photo), where its copies are (each can be played, if it's a video
 * on the phone, or deleted), saving another copy, and its jobs in the batch. Everything here
 * follows the copies index and the batch, so it changes as copies are saved and deleted.
 */
@Composable
fun ClipScreen(vm: AppViewModel, id: String, onBatch: () -> Unit, onServers: () -> Unit, onBack: () -> Unit) {
    val media = vm.mediaById(id) ?: run {
        Screen("Clip", onBack = onBack) { Text("This clip is no longer listed.", Modifier.padding(it).padding(16.dp)) }
        return
    }
    val cameraState by vm.cameraState.collectAsState()
    val clips by vm.catalogue.collectAsState()
    // On the camera that's connected now, so it can be streamed and fetched from there.
    val online = cameraState is CameraState.Online && vm.onConnectedCamera(media)
    val clip = clips[media.id]
    val index by vm.copyIndex.collectAsState()
    val copies = vm.copiesOf(media, index)
    LaunchedEffect(id) {
        vm.refreshCopies()
        vm.checkServerCopies(media)
    }
    HoldCameraWhileVisible(vm)

    // The copies on the phone the player can play, best first: the original, then stabilized, then the preview.
    val playable = index[media.id].orEmpty().filter { media.isVideo && it.isLocal }
        .sortedBy { when (it.version) { ClipVersion.Original -> 0; is ClipVersion.Stabilized -> 1; ClipVersion.Preview -> 2 } }
    var chosen by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val cameraPlayable = online && media.hasLrv
    val playing = chosen?.takeIf { c -> c == CAMERA_PREVIEW && cameraPlayable || playable.any { it.id == c } }
        ?: if (cameraPlayable) CAMERA_PREVIEW else playable.firstOrNull()?.id

    Screen(media.name, onBack = onBack, actions = { BatchButton(vm, onBatch) }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                when {
                    !media.isVideo -> PhotoView(
                        vm, media, online,
                        fromCamera = online && index[media.id].orEmpty().none { it.isLocal && it.version == ClipVersion.Original },
                    )
                    playing == CAMERA_PREVIEW -> CameraPreview(vm, media)
                    playing != null -> VideoPlayer(playable.first { it.id == playing }.location, vm)
                    else -> Column(
                        Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val connectable = !online && clip?.onCamera != false
                        Text(
                            when {
                                connectable -> "Connect to the camera to play this clip, or save a copy to this phone"
                                !online -> "Not on the camera any more. Save a copy to this phone to play it here."
                                else -> "The camera has no preview of this clip to play"
                            },
                            color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        if (connectable) {
                            val joining = cameraState is CameraState.Working
                            Button(onClick = { vm.connectCamera() }, enabled = !joining) { Text(if (joining) "Connecting…" else "Connect to camera") }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Copies(vm, media, clip, online, copies, playing, cameraPlayable, onPlay = { chosen = it })
                HorizontalDivider()
                SaveSection(vm, media, clip?.onCamera != false, index[media.id].orEmpty(), onServers)
                ClipJobs(vm, media, onBatch)
            }
        }
    }
}

/**
 * Saving a copy: which version (videos: the original, the camera's preview, or stabilized, and
 * how), downloaded and/or uploaded (where to is set under Storage and Servers). Queued in the batch.
 */
@Composable
private fun SaveSection(vm: AppViewModel, media: CameraMedia, onCamera: Boolean, saved: List<CopyEntity>, onServers: () -> Unit) {
    val uploadServer by vm.servers.defaultId.collectAsState()
    var variant by rememberSaveable(media.id) { mutableStateOf(JobVariant.Original) }
    var method by rememberSaveable(media.id) { mutableStateOf(StabilizationChoice.Suggested) }
    var toPhone by remember { mutableStateOf(vm.saveChoices.toPhone) }
    var toServer by remember { mutableStateOf(vm.saveChoices.toServer) }
    var needServer by remember { mutableStateOf(false) }

    val version = when (variant) {
        JobVariant.Original -> ClipVersion.Original
        JobVariant.Preview -> ClipVersion.Preview
        JobVariant.Stabilized -> ClipVersion.Stabilized(method)
    }
    val existing = saved.filter { it.version == version }
    val onPhone = existing.any { it.isLocal }
    // Uploaded counts only on the server uploads go to now. Neither blocks saving again: the batch
    // looks for itself, and finishes as "already there" if it is.
    val onServer = existing.any { it.place == CopyPlace.Server && it.serverId == uploadServer && uploadServer != null }
    // Off the camera, a copy is made from one on the phone or a server: the preview from the
    // preview, the rest from the original.
    val source = if (variant == JobVariant.Preview) ClipVersion.Preview else ClipVersion.Original
    val possible = onCamera || saved.any { it.version == source && (it.isLocal || vm.servers.get(it.serverId) != null) }

    // The originals stabilizing could read, best first (the order the batch picks in by itself): on
    // the phone, the camera, then servers. Offered as a choice only when there's more than one.
    // Named by where they are, briefly: the device ("Phone", "SanDisk SD card"), the camera, the server.
    val originals = buildList {
        saved.filter { it.isLocal && it.version == ClipVersion.Original }.forEach { add("copy:${it.id}" to it.placeLabel.substringBefore(" · ")) }
        if (onCamera) add("camera" to "${vm.cameraOf(media)?.name ?: "The camera"} (camera)")
        saved.filter { it.place == CopyPlace.Server && it.version == ClipVersion.Original }.forEach { c ->
            vm.servers.get(c.serverId)?.let { add("copy:${c.id}" to it.name) }
        }
    }.let { list ->
        // Two copies in the same place (two folders on the phone, say) get a number to tell them apart.
        list.map { (key, label) ->
            val same = list.filter { it.second == label }
            key to if (same.size > 1) "$label (${same.indexOfFirst { it.first == key } + 1})" else label
        }
    }
    var chosenOriginal by rememberSaveable(media.id) { mutableStateOf<String?>(null) }
    val stabilizeFrom = chosenOriginal?.takeIf { key -> originals.any { it.first == key } } ?: originals.firstOrNull()?.first

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Save a copy", style = MaterialTheme.typography.titleMedium)
        if (media.isVideo) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = variant == JobVariant.Original, onClick = { variant = JobVariant.Original }, label = { Text("Original") })
                if (media.hasLrv) {
                    FilterChip(selected = variant == JobVariant.Preview, onClick = { variant = JobVariant.Preview }, label = { Text("Preview") })
                }
                FilterChip(
                    selected = variant == JobVariant.Stabilized, onClick = { variant = JobVariant.Stabilized }, label = { Text("Stabilized") },
                    enabled = vm.quik.engineAvailable,
                )
            }
            if (variant == JobVariant.Stabilized) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 360 clips (MAX2 .360 files) have their own kinds.
                    StabilizationChoice.entries.filter { it != StabilizationChoice.Off && it.fits(media.extension == "360") }.forEach { c ->
                        FilterChip(selected = method == c, onClick = { method = c }, label = { Text(c.label) })
                    }
                }
            }
            Hint(
                when {
                    variant == JobVariant.Original -> "The full-quality file, as the camera recorded it (${formatBytes(media.sizeBytes)})."
                    variant == JobVariant.Preview -> "The camera's low-res copy (${formatBytes(media.lrvSizeBytes)}): small and quick to share."
                    else -> "Made from the original by GoPro's engine. Rendering takes a while."
                },
            )
            if (!vm.quik.engineAvailable) Hint(NO_ENGINE)
            if (variant == JobVariant.Stabilized && originals.size > 1) {
                var picking by remember { mutableStateOf(false) }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Stabilize from", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    Box {
                        androidx.compose.material3.OutlinedButton(onClick = { picking = true }) {
                            Text(originals.firstOrNull { it.first == stabilizeFrom }?.second.orEmpty())
                            Icon(androidx.compose.material.icons.Icons.Filled.ArrowDropDown, null)
                        }
                        androidx.compose.material3.DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                            originals.forEach { (key, label) ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { chosenOriginal = key; picking = false })
                            }
                        }
                    }
                    InfoButton(Explainers.stabilizeFrom)
                }
            }
        }
        if (!possible) Hint("It's not on the camera any more, and there's no ${source.label.lowercase()} on this phone or a server to make it from.")
        SaveRows(vm, 1, toPhone, { toPhone = it }, toServer, { toServer = it }, if (onPhone) 1 else 0, if (onServer) 1 else 0)
        Button(
            onClick = {
                vm.saveChoices.toPhone = toPhone
                vm.saveChoices.toServer = toServer
                val queued = vm.save(
                    listOf(media), variant, if (variant == JobVariant.Stabilized) method else null, toPhone, toServer, UploadOptions(),
                    source = stabilizeFrom.takeIf { variant == JobVariant.Stabilized && originals.size > 1 },
                )
                if (!queued) needServer = true
            },
            enabled = (toPhone || toServer) && possible,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(
                when {
                    toPhone && toServer -> "Download & upload"
                    toServer -> "Upload"
                    toPhone -> "Download"
                    else -> "Choose Download or Upload"
                },
            )
        }
    }
    if (needServer) ServerNeededDialog(vm, onSetUp = { needServer = false; onServers() }, onDismiss = { needServer = false })
}

/** This clip's jobs in the batch that are still to do, or failed: what's happening to it now. */
@Composable
private fun ClipJobs(vm: AppViewModel, media: CameraMedia, onBatch: () -> Unit) {
    val jobs by vm.batchJobs.collectAsState()
    val live by vm.batch.live.collectAsState()
    val scope = rememberCoroutineScope()
    val mine = jobs.filter { it.folder == media.folder && it.name == media.name && (it.isActive || it.state == JobState.Failed) }
    if (mine.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("In the batch", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onBatch) { Text("Open batch") }
        }
        mine.forEach { job ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${job.title} ${destination(vm, job)}", style = MaterialTheme.typography.bodyMedium)
                        val state = when (job.state) {
                            JobState.Failed -> job.error ?: "Failed"
                            JobState.Waiting -> job.waitingReason ?: "Waiting"
                            JobState.Running -> null
                            else -> "Queued"
                        }
                        state?.let {
                            Text(
                                it, style = MaterialTheme.typography.bodySmall,
                                color = if (job.state == JobState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (job.state == JobState.Failed) {
                        TextButton(onClick = { scope.launch { vm.batch.retry(job.id) } }) { Text("Retry") }
                    } else {
                        TextButton(onClick = { scope.launch { vm.batch.cancel(job.id) } }) { Text("Cancel") }
                    }
                }
                JobProgress(job, live[job.id])
            }
        }
    }
}

/**
 * Where this clip is: the camera's files (the clip, and its preview), then every copy OpenPro
 * knows of, each with its version (original, preview, stabilized and how) and place. Videos on the
 * phone, and the camera's preview, can be played; each copy that can be reached has a delete
 * button, which always asks first.
 */
@Composable
private fun Copies(
    vm: AppViewModel, media: CameraMedia, clip: com.openpro.copies.ClipEntity?, connected: Boolean, copies: List<ClipCopy>,
    playing: String?, cameraPlayable: Boolean, onPlay: (String) -> Unit,
) {
    var deleting by remember { mutableStateOf<ClipCopy?>(null) }
    val context = LocalContext.current
    val camera = vm.cameraCopy(media)
    val all = if (clip?.onCamera != false) listOf(camera) + copies else copies
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Where it is", style = MaterialTheme.typography.titleMedium)
        // Gone from the camera (deleted, or the card formatted): only its copies are left.
        if (clip?.onCamera != false) {
            val name = vm.cameraOf(media)?.name ?: "the camera"
            val listed = clip?.lastSeenAt?.takeIf { !connected && it > 0 }?.let {
                "Last listed" to android.text.format.DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
            }
            val recorded = ClipNames.stamp(media)?.let { "Recorded" to recordedText(media) }
            val title = if (media.isVideo) ClipVersion.Original.label else "Photo"
            CopyRow(
                Icons.Filled.PhotoCamera, title, "On $name", media.sizeBytes, onDelete = { deleting = camera },
                details = listOfNotNull(
                    "Version" to title, "Where" to "On $name", "Path on the camera" to "${media.folder}/${media.name}",
                    "Size" to exactSize(media.sizeBytes), recorded, listed,
                ),
            )
            if (media.hasLrv) {
                // The camera deletes a clip's preview only with the clip itself.
                CopyRow(
                    Icons.Filled.PhotoCamera, ClipVersion.Preview.label, "On $name", media.lrvSizeBytes, onDelete = null,
                    playing = if (cameraPlayable) playing == CAMERA_PREVIEW else null, onPlay = { onPlay(CAMERA_PREVIEW) },
                    deleteDisabled = "The camera only deletes the preview along with the clip",
                    details = listOfNotNull(
                        "Version" to ClipVersion.Preview.label, "Where" to "On $name", "Path on the camera" to "${media.folder}/${media.lrvName}",
                        "Size" to exactSize(media.lrvSizeBytes), recorded, listed,
                    ),
                )
            }
        }
        if (copies.isEmpty()) {
            Text("No copies saved yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        copies.forEach { c ->
            val icon = when (val p = c.place) {
                ClipCopy.Place.Camera -> Icons.Filled.PhotoCamera
                is ClipCopy.Place.Phone -> if (p.card) Icons.Filled.SdCard else Icons.Filled.PhoneAndroid
                is ClipCopy.Place.Server -> Icons.Filled.CloudDone
            }
            val indexed = (c.ref as? ClipCopy.Ref.Indexed)?.copy
            val local = indexed?.takeIf { it.isLocal }
            val saved = local?.takeIf { media.isVideo }
            val onServer = c.place is ClipCopy.Place.Server
            val title = if (media.isVideo) c.version.label else "Photo"
            CopyRow(
                // The place alone ("Phone gallery", the server's name); its folder is in the details.
                icon, title, c.where.substringBefore(" · "), c.sizeBytes, onDelete = c.ref?.let { { deleting = c } },
                playing = saved?.let { playing == it.id }, onPlay = { saved?.let { onPlay(it.id) } },
                onOpen = local?.let { copy -> { openWith(context, copy) } },
                details = listOfNotNull(
                    "Version" to title,
                    "Where" to c.where,
                    c.file?.let { (if (onServer) "Path on the server" else "File name") to it },
                    c.sizeBytes?.let { "Size" to exactSize(it) },
                    indexed?.let { (if (onServer) "Uploaded" else "Saved") to dateTime(it.savedAt) },
                    ClipNames.stamp(media)?.let { "Recorded" to recordedText(media) },
                    indexed?.sha1?.let { "SHA-1" to it },
                ),
            )
        }
    }
    deleting?.let { c ->
        DeleteConfirm(
            chosen = listOf(media to c),
            all = all.map { media to it },
            onConfirm = { vm.deleteCopies(listOf(media to c)); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * One copy: its version and size, and where in a word. [playing] (null: it can't be played) shows a
 * play button, filled while the player shows this copy; an info button shows [details] (paths, exact
 * size, dates), with [onOpen] (a copy on the phone) offering to open it in another app; [onDelete]
 * (if it can be deleted) a small bin button at the end.
 */
@Composable
private fun CopyRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, where: String, size: Long?, onDelete: (() -> Unit)?,
    playing: Boolean? = null, onPlay: () -> Unit = {}, deleteDisabled: String? = null, onOpen: (() -> Unit)? = null,
    details: List<Pair<String, String>>,
) {
    var showDetails by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(title, size?.let(::formatBytes)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                color = if (playing == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (playing != null) {
            IconButton(onClick = onPlay, Modifier.size(36.dp)) {
                if (playing) {
                    Icon(Icons.Filled.PlayCircle, "Playing", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Outlined.PlayCircle, "Play this copy", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        IconButton(onClick = { showDetails = true }, Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Info, "Details", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onDelete != null || deleteDisabled != null) {
            IconButton(onClick = { onDelete?.invoke() }, Modifier.size(36.dp), enabled = onDelete != null) {
                Icon(
                    Icons.Outlined.Delete, deleteDisabled ?: "Delete this copy", Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (onDelete != null) 1f else 0.38f),
                )
            }
        }
    }
    if (showDetails) CopyDetails(title, details, onOpen) { showDetails = false }
}

/**
 * A copy's details, one per line: a label, and a value that can be selected and copied (paths,
 * checksums). With [onOpen], an "Open with…" button too.
 */
@Composable
private fun CopyDetails(title: String, details: List<Pair<String, String>>, onOpen: (() -> Unit)?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = onOpen?.let { open -> { TextButton(onClick = { open(); onDismiss() }) { Text("Open with…") } } },
        title = { Text(title) },
        text = {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    details.forEach { (label, value) ->
                        Column {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
    )
}

/**
 * Hands a copy saved on the phone (in the gallery or a picked folder) to another app the user
 * chooses (a player, an editor, a file manager), with read access to that one file.
 */
private fun openWith(context: android.content.Context, copy: com.openpro.copies.CopyEntity) {
    val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
        .setDataAndType(android.net.Uri.parse(copy.location), ClipNames.mimeType(copy.fileName))
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(android.content.Intent.createChooser(view, "Open with"))
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "No app here can open it", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** "1.2 GB (1,234,567,890 bytes)". */
private fun exactSize(bytes: Long) = "${formatBytes(bytes)} (${java.text.NumberFormat.getIntegerInstance().format(bytes)} bytes)"

private fun dateTime(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(millis))

/** When the clip was recorded, by the camera's clock (local time stored as if UTC, see [ClipNames.stamp]). */
private fun recordedText(media: CameraMedia): String =
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
        .format(java.time.LocalDateTime.ofEpochSecond(media.createdSec, 0, java.time.ZoneOffset.UTC))

/**
 * A photo in full ([com.openpro.media.CameraPhotoFetcher]: the phone's copy, or the camera's file,
 * cached after the first look), with a spinner while it loads and the reason if it can't.
 */
@Composable
private fun PhotoView(vm: AppViewModel, media: CameraMedia, online: Boolean, fromCamera: Boolean) {
    val context = LocalContext.current
    // The full photo is fetched for this page only; it goes when the page does.
    DisposableEffect(media.id) { onDispose { com.openpro.media.CameraPhotoFetcher.forget(context.cacheDir, media) } }
    var failure by remember(media.id, online) { mutableStateOf<String?>(null) }
    var loading by remember(media.id, online) { mutableStateOf(true) }
    // From the camera, it's one of the camera's streams: taken until the photo is in, then given back.
    var loaded by remember(media.id) { mutableStateOf(false) }
    val mine = rememberCameraStream("a photo is opening", wanted = fromCamera && !loaded)
    val other by com.openpro.camera.http.CameraStreams.holder.collectAsState()
    if (fromCamera && !loaded && !mine) {
        Box(Modifier.fillMaxSize()) { if (other != null) CameraBusy(vm, Modifier.align(Alignment.Center)) }
        return
    }
    Box(Modifier.fillMaxSize()) {
        coil3.compose.AsyncImage(
            // [online] in the key retries a failed load once the camera's Wi-Fi is up.
            model = remember(media.id, online) { com.openpro.media.CameraPhoto(media) },
            contentDescription = media.name,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onLoading = { loading = true; failure = null },
            onSuccess = { loading = false; loaded = true },
            onError = { loading = false; failure = it.result.throwable.message ?: "Couldn't load the photo" },
        )
        if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center))
        failure?.let { Text(it, Modifier.align(Alignment.Center).padding(16.dp), color = Color.White) }
    }
}

/** The camera's preview of the clip, streamed: one of the camera's streams, so only while no one else has it. */
@Composable
private fun CameraPreview(vm: AppViewModel, media: CameraMedia) {
    val mine = rememberCameraStream("a clip's preview is playing", wanted = true)
    val other by com.openpro.camera.http.CameraStreams.holder.collectAsState()
    Box(Modifier.fillMaxSize()) {
        when {
            mine -> VideoPlayer(CameraApi.lowResUrl(media), vm)
            other != null -> CameraBusy(vm, Modifier.align(Alignment.Center))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(uri: String, vm: AppViewModel) {
    val context = LocalContext.current
    val player = remember(uri) {
        val cameraClient = vm.camera.api?.client
        val dataSource = if (uri.startsWith("http") && cameraClient != null) OkHttpDataSource.Factory(cameraClient)
        else DefaultDataSource.Factory(context)
        // Small buffer: stream only a little ahead of playback, to keep load on the camera low.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 15_000, 1_500, 2_500)
            .build()
        ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(uri)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
}

