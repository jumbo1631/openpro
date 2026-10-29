package com.openpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.compose.AsyncImage
import coil3.request.crossfade
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Visibility
import kotlinx.coroutines.launch
import com.openpro.media.CameraMedia

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    vm: AppViewModel, onOpen: (String) -> Unit, onBatch: () -> Unit, onServers: () -> Unit, onStats: () -> Unit, onBack: () -> Unit,
) {
    val all by vm.media.collectAsState()
    val clips by vm.catalogue.collectAsState()
    val loading by vm.loadingMedia.collectAsState()
    val error by vm.mediaError.collectAsState()
    val transfers by vm.transfers.collectAsState()
    val options by vm.galleryOptions.options.collectAsState()
    val selection by vm.selection.collectAsState()
    val online = vm.cameraState.collectAsState().value is com.openpro.camera.CameraState.Online
    val index by vm.copyIndex.collectAsState()
    val lengths by vm.durations.known.collectAsState()
    val media = remember(all, clips, index, options.source, options.camera) { options.filter(all, clips, index) }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshCopies() }
    // Thumbnail quality is a Storage setting; null shows placeholders and fetches nothing.
    val quality by vm.storage.thumbQuality.collectAsState()
    var showOptions by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<List<CameraMedia>?>(null) }
    val shelves = remember(media, options.sort, options.grouping) { options.shelves(media) }
    val selecting = selection.isNotEmpty()
    HoldCameraWhileVisible(vm)
    androidx.activity.compose.BackHandler(enabled = selecting) { vm.clearSelection() }

    // Tap opens a clip, or toggles it while selecting; long-press starts selecting.
    val tap: (CameraMedia) -> Unit = { item -> if (selecting) vm.toggleSelected(item.id) else onOpen(item.id) }
    val longPress: (CameraMedia) -> Unit = { item -> vm.toggleSelected(item.id) }

    Screen(
        if (selecting) "${selection.size} selected" else "Media",
        onBack = if (selecting) ({ vm.clearSelection() }) else onBack,
        actions = {
            if (selecting) {
                val all = media.map { it.id }
                IconButton(onClick = { deleting = media.filter { it.id in selection } }) { Icon(Icons.Outlined.Delete, "Delete…") }
                IconButton(onClick = { vm.setSelected(all, selection.size < all.size) }) {
                    Icon(Icons.Filled.SelectAll, if (selection.size < all.size) "Select all" else "Select none")
                }
            } else {
                IconButton(onClick = onStats) { Icon(Icons.Filled.BarChart, "Stats") }
                BatchButton(vm, onBatch)
                IconButton(onClick = { showOptions = true }) { Icon(Icons.Filled.Tune, "Sort and view options") }
                IconButton(onClick = { vm.connectCamera() }) { Icon(Icons.Filled.Refresh, "Refresh from the camera") }
            }
        },
        bottomBar = { if (selecting) SelectionBar(vm, onServers) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
        BatchPausedBanner(vm)
        if (!selecting) SourceFilters(vm, options, clips)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && all.isEmpty() -> GallerySkeleton(options)
                error != null && all.isEmpty() -> JoinHelp(vm, error!!, Modifier.align(Alignment.Center))
                media.isEmpty() -> EmptyGallery(vm, filtered = all.isNotEmpty(), Modifier.align(Alignment.Center))
                options.layout == Layout.Grid -> LazyVerticalGrid(
                    GridCells.Adaptive(options.tileSize.minWidthDp.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for ((shelf, items) in shelves) {
                        if (shelf != null) {
                            item(key = "shelf:$shelf", span = { GridItemSpan(maxLineSpan) }) {
                                ShelfHeader(shelf, items, selection, selecting, vm)
                            }
                        }
                        items(items, key = { it.id }) { item ->
                            if (TileField.Duration in options.fields) {
                                androidx.compose.runtime.LaunchedEffect(item.id, online) { vm.durations.request(item) }
                            }
                            Tile(
                                item, vm.onConnectedCamera(item), options.fields, quality, vm.copies(item, index), lengths[item.id], transfers[item.id]?.fraction,
                                selected = if (selecting) item.id in selection else null,
                                onClick = { tap(item) }, onLongClick = { longPress(item) },
                            )
                        }
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    for ((shelf, items) in shelves) {
                        if (shelf != null) stickyHeader(key = "shelf:$shelf") { ShelfHeader(shelf, items, selection, selecting, vm) }
                        items(items, key = { it.id }) { item ->
                            androidx.compose.runtime.LaunchedEffect(item.id, online) { vm.durations.request(item) }
                            ListRow(
                                item, vm.onConnectedCamera(item), quality, vm.copies(item, index), lengths[item.id], transfers[item.id]?.fraction,
                                selected = if (selecting) item.id in selection else null,
                                onClick = { tap(item) }, onLongClick = { longPress(item) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        }
    }

    deleting?.let { items -> DeleteSheet(vm, items, onDismiss = { deleting = null }) }
    if (showOptions) {
        ModalBottomSheet(onDismissRequest = { showOptions = false }) {
            OptionsSheet(options) { change -> vm.galleryOptions.update(change) }
        }
    }
}

/**
 * A line under the connection bar while the batch is paused for browsing the camera, with Resume.
 * (Whether the phone is on the camera's Wi-Fi is the connection bar's to show, not this.)
 */
@Composable
private fun BatchPausedBanner(vm: AppViewModel) {
    val state by vm.cameraState.collectAsState()
    val config by vm.batchConfig.collectAsState()
    val active by vm.activeJobCount.collectAsState()
    if (state !is com.openpro.camera.CameraState.Online || !config.paused || active == 0) return
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Batch paused while you browse ($active waiting)", Modifier.weight(1f).padding(vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        androidx.compose.material3.TextButton(onClick = { vm.batch.setPaused(false) }) { Text("Resume") }
    }
}

/**
 * Which clips to show: All, or those on a camera (as last listed), on this phone, or on a server.
 * With clips from more than one camera, a camera picker too.
 */
@Composable
private fun SourceFilters(vm: AppViewModel, options: GalleryOptions, clips: Map<String, com.openpro.copies.ClipEntity>) {
    val serials = remember(clips) { clips.values.mapNotNull { it.cameraSerial }.distinct() }
    var picking by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (serials.size > 1 || options.camera != null) {
            Box {
                FilterChip(
                    selected = options.camera != null,
                    onClick = { picking = true },
                    label = { Text(options.camera?.let { vm.camera.saved.bySerial(it)?.name ?: "Camera" } ?: "All cameras") },
                    leadingIcon = { Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp)) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
                )
                androidx.compose.material3.DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("All cameras") },
                        onClick = { picking = false; vm.galleryOptions.update { it.copy(camera = null) } },
                    )
                    serials.forEach { serial ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(vm.camera.saved.bySerial(serial)?.name ?: "Camera …${serial.takeLast(4)}") },
                            onClick = { picking = false; vm.galleryOptions.update { it.copy(camera = serial) } },
                        )
                    }
                }
            }
        }
        Source.entries.forEach { source ->
            FilterChip(
                selected = options.source == source,
                onClick = { vm.galleryOptions.update { it.copy(source = source) } },
                label = { Text(source.label) },
            )
        }
    }
}

/** Nothing to show: nothing seen yet (connect to a camera), or nothing matching the filters. */
@Composable
private fun EmptyGallery(vm: AppViewModel, filtered: Boolean, modifier: Modifier) {
    val cameras by vm.camera.saved.cameras.collectAsState()
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            when {
                filtered -> "Nothing here with these filters."
                cameras.isEmpty() -> "No clips yet. Add a camera on the home screen to browse it."
                else -> "No clips yet. Connect to your camera to browse it."
            },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (!filtered && cameras.isNotEmpty()) androidx.compose.material3.Button(onClick = { vm.connectCamera() }) { Text("Connect to camera") }
    }
}

/** What to do with the selected clips: queue them as batch jobs. Never fetches anything by itself. */
@Composable
private fun SelectionBar(vm: AppViewModel, onServers: () -> Unit) {
    var custom by remember { mutableStateOf<List<CameraMedia>?>(null) }
    var needServer by remember { mutableStateOf(false) }
    fun selected() = vm.media.value.filter { it.id in vm.selection.value }

    // One-tap versions of the common cases, with sensible defaults: the original or the suggested
    // stabilization, and nothing deleted. "Custom…" opens the full Save sheet.
    fun quick(toPhone: Boolean, stabilized: Boolean) {
        val queued = vm.save(
            selected(),
            if (stabilized) com.openpro.batch.JobVariant.Stabilized else com.openpro.batch.JobVariant.Original,
            if (stabilized) com.openpro.quik.StabilizationChoice.Suggested else null,
            toPhone = toPhone,
            toServer = !toPhone,
            options = com.openpro.batch.UploadOptions(),
        )
        if (!queued) needServer = true
    }
    // Stabilizing is for videos: with only photos selected, the stabilized actions aren't offered.
    val selection by vm.selection.collectAsState()
    val anyVideo = vm.media.collectAsState().value.any { it.id in selection && it.isVideo }
    androidx.compose.material3.BottomAppBar {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BarAction(Icons.Filled.Download, "Download") { quick(toPhone = true, stabilized = false) }
            if (anyVideo) BarAction(Icons.Filled.AutoFixHigh, "Download\nstabilized") { quick(toPhone = true, stabilized = true) }
            BarAction(Icons.Filled.CloudUpload, "Upload") { quick(toPhone = false, stabilized = false) }
            if (anyVideo) BarAction(Icons.Filled.CloudSync, "Upload\nstabilized") { quick(toPhone = false, stabilized = true) }
            BarAction(Icons.Filled.Tune, "Custom…") { custom = selected() }
        }
    }
    custom?.let { items -> SaveSheet(vm, items, onDismiss = { custom = null }, onServers = onServers) }
    if (needServer) ServerNeededDialog(vm, onSetUp = { needServer = false; onServers() }, onDismiss = { needServer = false })
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null)
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun StabilizationPicker(onDismiss: () -> Unit, onPick: (com.openpro.quik.StabilizationChoice) -> Unit) {
    var choice by remember { mutableStateOf(com.openpro.quik.StabilizationChoice.Suggested) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stabilization") },
        text = {
            Column {
                com.openpro.quik.StabilizationChoice.entries.filter { it != com.openpro.quik.StabilizationChoice.Off }.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { choice = c }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = choice == c, onClick = { choice = c })
                        Text(c.label)
                    }
                }
                Text(
                    "Rendered on the phone with GoPro's engine, one clip at a time.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onPick(choice) }) { Text("Add to batch") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OptionsSheet(options: GalleryOptions, update: ((GalleryOptions) -> GalleryOptions) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Sort by", style = MaterialTheme.typography.titleMedium)
        SortOrder.entries.forEach { order ->
            Row(
                Modifier.fillMaxWidth().clickable { update { it.copy(sort = order) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = options.sort == order, onClick = { update { it.copy(sort = order) } })
                Text(order.label)
            }
        }

        Text("Group by", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Grouping.entries.forEach { g ->
                FilterChip(
                    selected = options.grouping == g, onClick = { update { it.copy(grouping = g) } }, label = { Text(g.label) },
                    enabled = options.canShelve,
                )
            }
        }
        if (!options.canShelve) Text("Only when sorting by date", style = MaterialTheme.typography.bodySmall)

        Text("Display", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Layout.entries.forEach { layout ->
                FilterChip(selected = options.layout == layout, onClick = { update { it.copy(layout = layout) } }, label = { Text(layout.label) })
            }
        }


        if (options.layout == Layout.Grid) {
            Text("Grid item size", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TileSize.entries.forEach { size ->
                    FilterChip(selected = options.tileSize == size, onClick = { update { it.copy(tileSize = size) } }, label = { Text(size.label) })
                }
            }

            Text("Show on grid items", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            TileField.entries.forEach { field ->
                fun toggle() = update { o -> o.copy(fields = if (field in o.fields) o.fields - field else o.fields + field) }
                Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = field in options.fields, onCheckedChange = { toggle() })
                    Text(field.label)
                }
            }
        }
    }
}

/**
 * Stand-ins for the gallery while the camera's media list loads, laid out like the real thing:
 * same grid size, a 16:9 preview each, and a line for each field the tiles will show.
 */
@Composable
private fun GallerySkeleton(options: GalleryOptions) {
    val widths = listOf(0.8f, 0.65f, 0.9f, 0.7f, 0.55f)
    if (options.layout == Layout.Grid) {
        LazyVerticalGrid(
            GridCells.Adaptive(options.tileSize.minWidthDp.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false,
        ) {
            if (options.shelved) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp)) { ShimmerLine(MaterialTheme.typography.titleMedium, 0.35f) }
                }
            }
            items(SKELETON_TILES) { i ->
                val f = options.fields
                Column {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).shimmer())
                    if (TileField.FileName in f) ShimmerLine(MaterialTheme.typography.bodyMedium, widths[i % widths.size])
                    if (TileField.Date in f || TileField.Time in f) ShimmerLine(MaterialTheme.typography.bodySmall, widths[(i + 2) % widths.size])
                    if (TileField.FileSize in f || TileField.PreviewSize in f) {
                        ShimmerLine(MaterialTheme.typography.bodySmall, widths[(i + 4) % widths.size] * 0.7f)
                    }
                }
            }
        }
    } else {
        Column(Modifier.padding(vertical = 4.dp)) {
            repeat(SKELETON_ROWS) { i ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(128.dp).aspectRatio(16f / 9f).shimmer())
                    Column(Modifier.weight(1f)) {
                        ShimmerLine(MaterialTheme.typography.bodyLarge, widths[i % widths.size])
                        ShimmerLine(MaterialTheme.typography.bodyMedium, widths[(i + 2) % widths.size])
                        ShimmerLine(MaterialTheme.typography.bodySmall, widths[(i + 4) % widths.size] * 0.6f)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

private const val SKELETON_TILES = 24
private const val SKELETON_ROWS = 10

/** A day's or month's heading. While selecting, a circle in front of it selects or deselects the whole shelf. */
@Composable
private fun ShelfHeader(label: String, items: List<CameraMedia>, selection: Set<String>, selecting: Boolean, vm: AppViewModel) {
    val ids = items.map { it.id }
    val all = selecting && ids.all { it in selection }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .then(if (selecting) Modifier.clickable { vm.setSelected(ids, !all) } else Modifier)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Icon(
                if (all) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                if (all) "Deselect $label" else "Select $label",
                tint = if (all) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
    }
}

private enum class ThumbState { Loading, Shown, Failed, Off }

private const val THUMB_FADE_MS = 150

/**
 * A clip's preview image, shimmering while it loads. With previews turned off (or none to be had)
 * it's a plain placeholder of the same size, so the layout stays the same and nothing is fetched.
 */
@Composable
private fun Thumbnail(
    media: CameraMedia, online: Boolean, quality: com.openpro.media.ThumbQuality?, copies: ClipCopies, transfer: Float?,
    modifier: Modifier, selected: Boolean? = null, lengthMs: Long? = null, showLength: Boolean = false,
) {
    val showImage = quality != null
    var state by remember(media.id, online, quality) { mutableStateOf(if (showImage) ThumbState.Loading else ThumbState.Off) }
    // Behind a fading-in image, the shimmer's own base colour, so the switch from shimmer doesn't show.
    val behind = if (state == ThumbState.Shown) MaterialTheme.colorScheme.surfaceVariant else Color.DarkGray
    Box(modifier.then(if (state == ThumbState.Loading) Modifier.shimmer() else Modifier.background(behind))) {
        val context = androidx.compose.ui.platform.LocalContext.current
        if (quality != null) {
            AsyncImage(
                // One decode size per quality for every layout, so changing grid size or list/grid
                // reuses the in-memory image instead of fetching it again.
                model = remember(media.id, online, quality) {
                    coil3.request.ImageRequest.Builder(context)
                        .data(com.openpro.media.CameraThumb(media, online, quality))
                        .size(quality.width, quality.height)
                        // A quick fade in when it arrives; images already in memory just appear.
                        .crossfade(THUMB_FADE_MS)
                        .build()
                },
                contentDescription = media.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onLoading = { state = ThumbState.Loading },
                onSuccess = { state = ThumbState.Shown },
                onError = { state = ThumbState.Failed },
            )
        }
        if (state == ThumbState.Off || state == ThumbState.Failed) {
            Icon(
                if (media.isVideo) Icons.Outlined.Movie else Icons.Outlined.Image,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }
        Badges(media, copies, Modifier.align(Alignment.TopEnd))
        if (selected != null) SelectMark(selected, Modifier.align(Alignment.TopStart))
        if (showLength) LengthLabel(lengthMs, Modifier.align(Alignment.BottomEnd).padding(4.dp).padding(bottom = if (transfer != null) 4.dp else 0.dp))
        if (transfer != null) {
            LinearProgressIndicator(progress = { transfer }, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun Badges(media: CameraMedia, copies: ClipCopies, modifier: Modifier) {
    Row(modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (media.chapter != null && media.chapter!! > 1) Badge("Ch ${media.chapter}")
        if (copies.cameraAway) IconBadge(Icons.Filled.PhotoCamera, "On the camera (not connected)", faded = true)
        if (copies.onPhone) IconBadge(Icons.Filled.PhoneAndroid, "On this phone")
        if (copies.onCard) IconBadge(Icons.Filled.SdCard, "On the SD card")
        if (copies.preview) IconBadge(Icons.Filled.Visibility, "Preview saved on this phone")
        if (copies.uploaded) IconBadge(Icons.Filled.CloudDone, "Uploaded to your server")
    }
}

/**
 * A clip's length over its thumbnail, on a rounded dark box so it reads over any picture. Until
 * the length is known it holds its place as "-:--", so it doesn't pop in.
 */
@Composable
private fun LengthLabel(lengthMs: Long?, modifier: Modifier) {
    Text(
        lengthMs?.let { com.openpro.media.ClipDurations.format(it) } ?: "-:--",
        modifier.background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (lengthMs != null) Color.White else Color.White.copy(alpha = 0.6f),
        maxLines = 1,
    )
}

/** A small white icon on a dark circle, readable over any thumbnail. */
@Composable
private fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, faded: Boolean = false) {
    androidx.compose.material3.Icon(
        icon,
        contentDescription = description,
        tint = Color.White.copy(alpha = if (faded) 0.5f else 1f),
        modifier = Modifier.background(Color(0xCC000000), androidx.compose.foundation.shape.CircleShape).padding(3.dp).size(14.dp),
    )
}

@Composable
private fun SelectMark(selected: Boolean, modifier: Modifier) {
    Icon(
        if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
        if (selected) "Selected" else "Not selected",
        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        modifier = modifier.padding(4.dp).background(Color(0x66000000), androidx.compose.foundation.shape.CircleShape),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    media: CameraMedia, online: Boolean, fields: Set<TileField>, quality: com.openpro.media.ThumbQuality?, copies: ClipCopies,
    lengthMs: Long?, transfer: Float?, selected: Boolean?, onClick: () -> Unit, onLongClick: () -> Unit,
) {
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected == true) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)) else Modifier),
    ) {
        Thumbnail(
            media, online, quality, copies, transfer, Modifier.fillMaxWidth().aspectRatio(16f / 9f), selected,
            lengthMs = lengthMs, showLength = TileField.Duration in fields && media.isVideo,
        )
        if (TileField.FileName in fields) Text(media.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        val dateTime = listOfNotNull(
            MediaTime.date(media).takeIf { TileField.Date in fields },
            MediaTime.time(media).takeIf { TileField.Time in fields && it.isNotEmpty() },
        ).joinToString("  ")
        if (dateTime.isNotEmpty()) Text(dateTime, style = MaterialTheme.typography.bodySmall)
        ClipFacts(
            size = media.sizeBytes.takeIf { TileField.FileSize in fields },
            previewSize = media.lrvSizeBytes.takeIf { TileField.PreviewSize in fields && media.hasLrv },
        )
    }
}

/**
 * The file's size, then the preview's size behind an eye icon in a faded tone: it's what streams
 * when you look at a clip, so it matters less than the file's own size.
 */
@Composable
private fun ClipFacts(size: Long?, previewSize: Long?, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall) {
    if (size == null && previewSize == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        val main = size?.let(::formatBytes).orEmpty()
        if (main.isNotEmpty()) Text(main, style = style, maxLines = 1)
        if (previewSize != null) {
            val faded = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            if (main.isNotEmpty()) androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.Visibility,
                contentDescription = "Preview size",
                tint = faded,
                modifier = Modifier.size(12.dp),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(2.dp))
            Text(formatBytes(previewSize), style = style, color = faded, maxLines = 1)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ListRow(
    media: CameraMedia, online: Boolean, quality: com.openpro.media.ThumbQuality?, copies: ClipCopies, lengthMs: Long?, transfer: Float?,
    selected: Boolean?, onClick: () -> Unit, onLongClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected == true) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) Checkbox(checked = selected, onCheckedChange = { onClick() })
        Thumbnail(media, online, quality, copies, transfer, Modifier.width(128.dp).aspectRatio(16f / 9f), lengthMs = lengthMs, showLength = media.isVideo)
        Column(Modifier.weight(1f)) {
            Text(media.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text("${MediaTime.date(media)}  ${MediaTime.time(media)}", style = MaterialTheme.typography.bodyMedium)
            ClipFacts(size = media.sizeBytes, previewSize = media.lrvSizeBytes.takeIf { media.hasLrv })
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        Modifier.background(Color(0xCC000000)).padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
    )
}

/** Shown when joining or reaching the camera failed: the error, the AP credentials, and a manual path. */
@Composable
private fun JoinHelp(vm: AppViewModel, error: String, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val credentials by vm.camera.credentials.collectAsState()
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(error, color = MaterialTheme.colorScheme.error)
        credentials?.let { c ->
            Text("Or join the camera's WiFi yourself, then tap Retry:", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text("Network: ${c.ssid}\nPassword: ${c.password}", style = MaterialTheme.typography.bodyLarge)
            }
            androidx.compose.material3.OutlinedButton(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI))
            }) { Text("Open WiFi settings") }
        }
        androidx.compose.material3.Button(onClick = { vm.browse() }) { Text("Retry") }
    }
}

/** Shown when the user asks to upload before setting up a server. */
@Composable
fun ServerNeededDialog(vm: AppViewModel, onSetUp: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.dropPendingUpload(); onDismiss() },
        title = { Text(ServerIntro.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ServerIntro.body)
                Text("Set one up now and these uploads are added to the batch as soon as it's saved.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onSetUp) { Text("Set up server") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { vm.dropPendingUpload(); onDismiss() }) { Text("Not now") } },
    )
}

/** Shows the view model's one-off messages as toasts. */
@Composable
fun MessageToasts(vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.messages.collect { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }
}
