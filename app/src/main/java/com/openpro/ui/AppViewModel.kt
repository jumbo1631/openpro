package com.openpro.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openpro.app
import com.openpro.camera.CameraState
import com.openpro.camera.ble.FoundCamera
import com.openpro.media.CameraMedia
import com.openpro.media.ClipNames
import com.openpro.quik.StabilizationChoice
import com.openpro.media.ClipVersion
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.app
    val camera = app.camera
    val storage = app.storage
    /** Video lengths for the gallery, fetched as tiles appear. */
    val durations = app.durations
    val downloader = app.downloader
    val quik = app.quik
    val galleryOptions = GalleryOptionsStore(application)

    // --- remote control ---
    val control = app.control
    private val _previewPort = MutableStateFlow<Int?>(null)
    /** Loopback UDP port the live preview arrives on, while it runs. */
    val previewPort: StateFlow<Int?> = _previewPort
    private val _previewStatus = MutableStateFlow<String?>(null)
    val previewStatus: StateFlow<String?> = _previewStatus

    private var previewJob: Job? = null
    /** The live preview's hold on the camera's one stream (see CameraStreams), while it runs. */
    private var previewLease: com.openpro.camera.http.CameraStreams.Holder? = null

    fun startPreview() {
        previewJob?.cancel()
        if (previewLease == null) {
            previewLease = com.openpro.camera.http.CameraStreams.tryAcquire(com.openpro.camera.http.CameraStreams.Who.Viewer, "the live preview is running")
        }
        if (previewLease == null) {
            val doing = com.openpro.camera.http.CameraStreams.holder.value?.doing ?: "it's busy"
            _previewStatus.value = "The camera can stream one thing at a time, and $doing. Pause the batch, then try again."
            return
        }
        previewJob = viewModelScope.launch {
            _previewStatus.value = "Starting the preview (turning on the camera's Wi-Fi if needed)…"
            try {
                _previewPort.value = app.preview.start()
                _previewStatus.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "preview", e)
                _previewPort.value = null
                _previewStatus.value = e.message ?: e.toString()
                app.scope.launch { app.preview.stop() }
            }
        }
    }

    /** Video datagrams received from the camera so far, or -1 when OpenPro can't count them (Camera Link). */
    fun previewPackets(): Long = app.preview.packets

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewLease?.let(com.openpro.camera.http.CameraStreams::release)
        previewLease = null
        if (_previewPort.value == null && _previewStatus.value == null) return
        _previewPort.value = null
        _previewStatus.value = null
        app.scope.launch { app.preview.stop() }
    }

    // --- batch ---
    val batch = app.batch
    val batchJobs = batch.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val batchConfig = batch.settings.config
    val net = app.net.status
    val servers = app.servers

    /** An upload the user asked for before any server was set up; queued once one is saved. */
    private var pendingUpload: (suspend (String) -> Int)? = null
    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-off messages for a toast (e.g. "Added 3 uploads to the batch"). */
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = _messages

    /** The Save sheet's remembered choices. */
    val saveChoices = com.openpro.batch.SaveChoices(application)

    /**
     * Queues what the Save sheet or clip page asked for: a download ([toPhone] only), an upload to
     * the server selected under Servers ([toServer]), or Download & upload (both, one job per clip).
     * Returns false if an upload needs a server that isn't set up yet: the request is kept, and
     * queued once one is saved.
     */
    fun save(
        items: List<CameraMedia>,
        variant: com.openpro.batch.JobVariant,
        choice: StabilizationChoice?,
        toPhone: Boolean,
        toServer: Boolean,
        options: com.openpro.batch.UploadOptions,
        /** Which copy to read (BatchRunner.sourceOf), when the user chose one; null for the best there is. */
        source: String? = null,
    ): Boolean {
        // What the batch does differently for some items (see BatchRepository.enqueue), said up front.
        val leftOut = if (variant == com.openpro.batch.JobVariant.Preview) items.count { !it.hasLrv } else 0
        val asIs = if (variant == com.openpro.batch.JobVariant.Stabilized) items.count { !it.isVideo } else 0
        val asked = items.size - leftOut
        val notes = listOfNotNull(
            asIs.takeIf { it > 0 }?.let { "$it photo${if (it == 1) "" else "s"} saved as ${if (it == 1) "it is" else "they are"}" },
            leftOut.takeIf { it > 0 }?.let { "$it without a preview left out" },
        )
        if (!toServer) {
            viewModelScope.launch {
                val added = batch.enqueue(items, com.openpro.batch.JobAction.Download, variant, choice, options = options, source = source) { placement(it) }
                clearSelection()
                val extra = listOfNotNull((asked - added).takeIf { it > 0 }?.let { "$it already queued" }) + notes
                _messages.tryEmit(
                    "Added $added download${if (added == 1) "" else "s"} to the batch" +
                        if (extra.isEmpty()) "" else extra.joinToString(", ", " (", ")"),
                )
            }
            return true
        }
        val opts = options.copy(keepOnPhone = toPhone)
        // Taken now: by the time a server is set up, another camera may be connected.
        val cams = items.associate { it.id to (cameraOf(it) ?: camera.current) }
        val queue: suspend (String) -> Int = { serverId ->
            val server = servers.get(serverId)
            batch.enqueue(items, com.openpro.batch.JobAction.Upload, variant, choice, serverId, opts, source) { placement(it, cams[it.id], server) }
                .also { clearSelection() }
        }
        val server = servers.defaultServer()
        if (server == null) {
            pendingUpload = queue
            return false
        }
        viewModelScope.launch { announce(queue(server.id), asked, server.name, notes) }
        return true
    }

    fun dropPendingUpload() {
        pendingUpload = null
    }

    /** A server was saved in setup: queue the upload that was waiting for one. */
    fun serverSaved(id: String) {
        val pending = pendingUpload ?: return
        pendingUpload = null
        val name = servers.get(id)?.name ?: "your server"
        viewModelScope.launch { announce(pending(id), null, name) }
    }

    private fun announce(added: Int, asked: Int?, server: String, notes: List<String> = emptyList()) {
        val skipped = asked?.let { it - added }?.takeIf { it > 0 }
        val extra = listOfNotNull(skipped?.let { "$it already queued" }) + notes
        _messages.tryEmit("Added $added upload${if (added == 1) "" else "s"} to $server to the batch" + if (extra.isEmpty()) "" else extra.joinToString(", ", " (", ")"))
    }
    val activeJobCount = batch.jobs.map { list -> list.count { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Media ids selected in the gallery; non-empty means selection mode. */
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection

    fun toggleSelected(id: String) = _selection.update { if (id in it) it - id else it + id }
    fun setSelected(ids: Collection<String>, selected: Boolean) = _selection.update { if (selected) it + ids else it - ids.toSet() }
    fun clearSelection() { _selection.value = emptySet() }

    val cameraState: StateFlow<CameraState> = camera.state
    val transfers = downloader.transfers

    private val _found = MutableStateFlow<List<FoundCamera>>(emptyList())
    val found: StateFlow<List<FoundCamera>> = _found
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private var scanJob: Job? = null

    /** Every clip seen on a camera, by id: which camera, and whether it's still on it. */
    val catalogue: StateFlow<Map<String, com.openpro.copies.ClipEntity>> = app.catalogue.clips

    /**
     * The gallery: every clip on a camera (as last listed, so it works without the camera), and
     * every clip with copies saved or uploaded, even once it's gone from the camera.
     */
    val media: StateFlow<List<CameraMedia>> = kotlinx.coroutines.flow.combine(app.catalogue.clips, app.copies.byClip) { clips, index ->
        clips.values.filter { it.onCamera || !index[it.id].isNullOrEmpty() }.map { it.toMedia() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _mediaError = MutableStateFlow<String?>(null)
    val mediaError: StateFlow<String?> = _mediaError
    private val _loadingMedia = MutableStateFlow(false)
    val loadingMedia: StateFlow<Boolean> = _loadingMedia

    fun startScan() {
        if (scanJob?.isActive == true || !camera.ble.enabled.value) return
        _found.value = emptyList()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                camera.scan().collect { cam ->
                    _found.update { list -> (list.filter { it.device.address != cam.device.address } + cam).sortedByDescending { it.rssi } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "scan", e)
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
    }

    fun connect(found: FoundCamera) {
        stopScan()
        camera.connect(found.device, found.name, found.modelId)
    }

    fun reconnectLast() = withBluetooth { camera.reconnectLast() }

    /** Connects to a camera added before. */
    fun reconnect(address: String) = withBluetooth {
        stopScan()
        camera.reconnect(address)
    }

    private val _bluetoothRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Asks the screen to show Android's "turn on Bluetooth" prompt; answered with [bluetoothAnswered]. */
    val bluetoothRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _bluetoothRequests
    private var afterBluetooth: (() -> Unit)? = null

    /** Runs [action] now if Bluetooth is on; otherwise asks to turn it on, and runs it once it is. */
    private fun withBluetooth(action: () -> Unit) {
        if (camera.ble.enabled.value) return action()
        afterBluetooth = action
        _bluetoothRequests.tryEmit(Unit)
    }

    fun bluetoothAnswered(turnedOn: Boolean) {
        val action = afterBluetooth
        afterBluetooth = null
        if (turnedOn) action?.invoke()
    }

    /** Asks to turn Bluetooth on, with nothing to do afterwards (the screen follows [GoProBle.enabled]). */
    fun askForBluetooth() = withBluetooth {}

    fun disconnect() = camera.disconnect(sleepCamera = true)

    /** Switches to another added camera: the one connected now is let go (and put to sleep) first. */
    fun switchCamera(address: String) = withBluetooth {
        viewModelScope.launch {
            val now = camera.address.value
            if (now != null && now != address) camera.disconnect(sleepCamera = true).join()
            camera.reconnect(address)
        }
    }

    /** Fetches the media list from the camera. Only Refresh (and first open) do this. */
    fun browse() = viewModelScope.launch {
        com.openpro.media.CameraThumbFetcher.clearFailures()
        _loadingMedia.value = true
        _mediaError.value = null
        try {
            val listing = try {
                camera.goOnline().mediaList()
            } catch (e: java.io.IOException) {
                if (!e.isNetworkPermissionError()) throw e
                Log.w(TAG, "camera network handle unusable; re-acquiring", e)
                camera.networkFailed()
                camera.goOnline().mediaList()
            }
            app.catalogue.listed(camera.current?.serial, listing)
        } catch (e: Exception) {
            // Joining the camera's Wi-Fi cancelled from the home screen isn't an error to show (a
            // timeout is, though Kotlin reports it as a cancellation too).
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) {
                Log.i(TAG, "browse cancelled")
            } else {
                Log.w(TAG, "browse", e)
                _mediaError.value = e.message ?: e.toString()
            }
        } finally {
            _loadingMedia.value = false
            _wifiWanted.value = false
        }
    }

    private val _leavingWifi = MutableStateFlow(false)
    /** Leaving the camera's Wi-Fi (the connection bar's switch turned off), for its spinner. */
    val leavingWifi: StateFlow<Boolean> = _leavingWifi

    /** Leaves the camera's Wi-Fi and turns the camera's hotspot off; Bluetooth stays connected. */
    fun goOffline() = viewModelScope.launch {
        _leavingWifi.value = true
        try {
            camera.goOffline()
        } finally {
            _leavingWifi.value = false
        }
    }

    /**
     * The connection bar's Cancel: stops connecting (over Bluetooth, or joining the camera's Wi-Fi).
     * If it's the batch switching networks, the batch is paused instead, which stops it.
     */
    fun cancelConnecting() {
        if (net.value.switching != null) batch.setPaused(true)
        _wifiWanted.value = false
        camera.cancelConnecting()
    }

    /**
     * Joins the camera's Wi-Fi to browse it, connecting over Bluetooth first (to the camera used
     * last) if need be. A busy batch is paused first, so it doesn't switch the Wi-Fi back to the
     * internet for an upload; the user resumes it from the Batch page.
     */
    fun connectCamera() {
        if (camera.state.value is CameraState.Online) {
            browse()
            return
        }
        // Bluetooth comes first: ask to turn it on, and carry on from here once it is.
        if (camera.state.value !is CameraState.Ready && !camera.ble.enabled.value) return withBluetooth { connectCamera() }
        // With Wi-Fi switching off the batch never takes the Wi-Fi back, so there's nothing to pause for.
        val config = batch.settings.config.value
        if (config.switchWifi && !config.paused && activeJobCount.value > 0) {
            batch.setPaused(true)
            _messages.tryEmit("Switching to the camera's Wi-Fi. Batch jobs are paused; resume them from the Batch page.")
        }
        val state = camera.state.value
        if (state is CameraState.Ready) {
            _wifiWanted.value = true
            browse()
            return
        }
        val last = camera.saved.last ?: run {
            _messages.tryEmit("Add a camera on the home screen first")
            return
        }
        _wifiWanted.value = true
        if (state !is CameraState.Working) camera.reconnect(last.address)
        viewModelScope.launch {
            val ready = kotlinx.coroutines.withTimeoutOrNull(60_000) {
                camera.state.first { it is CameraState.Ready || it is CameraState.Failed || it is CameraState.Disconnected }
            }
            when (ready) {
                is CameraState.Ready -> browse()
                else -> {
                    // Couldn't connect over Bluetooth on the way to the Wi-Fi: the connection bar says so.
                    if (ready is CameraState.Failed) _mediaError.value = ready.message
                    _wifiWanted.value = false
                }
            }
        }
    }

    private val _wifiWanted = MutableStateFlow(false)
    /**
     * The camera's Wi-Fi was asked for (the connection bar's switch, Connect in the gallery), so
     * connecting over Bluetooth first is part of connecting to it. Connecting over Bluetooth alone
     * (from the home screen) isn't, and doesn't spin the connection bar.
     */
    val wifiWanted: StateFlow<Boolean> = _wifiWanted

    init {
        // A fresh Bluetooth connection starts over: an old failure to reach the camera no longer applies.
        viewModelScope.launch {
            camera.ble.connected.collect { connected -> if (connected) _mediaError.value = null }
        }
    }

    fun mediaById(id: String): CameraMedia? = app.catalogue[id]?.toMedia() ?: media.value.firstOrNull { it.id == id }

    /** The clip is on the camera connected now, so it can be streamed, fetched or deleted there right away. */
    fun onConnectedCamera(media: CameraMedia) = cameraState.value is CameraState.Online && app.catalogue.onConnectedCamera(media)

    /** The added camera [media] was listed on, if known. */
    fun cameraOf(media: CameraMedia): com.openpro.camera.SavedCamera? = camera.saved.bySerial(app.catalogue[media.id]?.cameraSerial)

    /** Every copy OpenPro has saved or uploaded, by clip id. */
    val copyIndex: StateFlow<Map<String, List<com.openpro.copies.CopyEntity>>> = app.copies.byClip

    /** Drops records of saved copies that are gone (deleted in a gallery app, say). Asks only Android, never the network. */
    fun refreshCopies() = viewModelScope.launch { app.copies.prune() }

    /**
     * Checks [media]'s copies on servers are still there (one small request each), dropping the
     * records of any deleted there by hand. Only when the phone has internet uploads may use (the
     * mobile data setting): it never switches networks, waits or retries, and a failed request
     * changes nothing.
     */
    fun checkServerCopies(media: CameraMedia) = viewModelScope.launch {
        val allowMobile = batch.settings.config.value.uploadsOverMobileData
        for (copy in app.copies.of(media).filter { it.place == com.openpro.copies.CopyPlace.Server }) {
            val server = servers.get(copy.serverId) ?: continue
            if (!app.net.internet.value.usable(allowMobile)) continue
            runCatching { servers.client(server).stat(copy.location) }
                .onSuccess { if (it == null) app.copies.remove(copy) }
        }
    }

    /**
     * The Servers page's "Check uploaded files": which of OpenPro's uploads to [server] are still
     * there, by listing the folders they're in. Records of files deleted there are dropped. Says
     * what it found.
     */
    suspend fun checkUploads(server: com.openpro.server.ServerProfile): String = try {
        val client = servers.client(server)
        val (checked, gone) = app.copies.checkServer(server.id) { dir -> client.list(dir).filter { !it.isDirectory }.map { it.name } }
        when {
            checked == 0 -> "OpenPro hasn't uploaded anything to ${server.name} yet"
            gone == 0 -> "All ${if (checked == 1) "1 file is" else "$checked files are"} still on ${server.name}"
            else -> "$gone of $checked no longer on ${server.name}; removed from OpenPro's list"
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        "Couldn't check: " + describeServerError(e)
    }

    /** Where a clip has copies besides the camera, for the gallery's corner icons. */
    fun copies(media: CameraMedia, index: Map<String, List<com.openpro.copies.CopyEntity>>): ClipCopies {
        var phone = false
        var card = false
        val copies = index[media.id].orEmpty()
        // A saved preview gets its own (eye) icon; the phone and SD card icons are for full copies.
        copies.filter { it.isLocal && it.version != ClipVersion.Preview }.forEach { if (it.onRemovable) card = true else phone = true }
        return ClipCopies(
            cameraAway = app.catalogue[media.id]?.onCamera == true && !onConnectedCamera(media),
            onPhone = phone,
            onCard = card,
            uploaded = copies.any { it.place == com.openpro.copies.CopyPlace.Server },
            preview = copies.any { it.isLocal && it.version == ClipVersion.Preview },
        )
    }

    /**
     * Every copy of [media] OpenPro knows of besides the camera's: what the copies index records
     * (saved on the phone or uploaded), each with its version and where it is.
     */
    fun copiesOf(media: CameraMedia, index: Map<String, List<com.openpro.copies.CopyEntity>>): List<ClipCopy> = buildList {
        for (copy in index[media.id].orEmpty()) {
            val place = if (copy.place == com.openpro.copies.CopyPlace.Server) ClipCopy.Place.Server(copy.serverId) else ClipCopy.Place.Phone(copy.onRemovable)
            // A server's name can change after the upload; show today's.
            val where = if (copy.place == com.openpro.copies.CopyPlace.Server) {
                servers.get(copy.serverId)?.name ?: "A server you've since removed"
            } else {
                copy.placeLabel
            }
            val reachable = copy.place != com.openpro.copies.CopyPlace.Server || servers.get(copy.serverId) != null
            add(
                ClipCopy(
                    copy.version, place, where, if (copy.place == com.openpro.copies.CopyPlace.Server) copy.location else copy.fileName,
                    copy.sizeBytes, if (reachable) ClipCopy.Ref.Indexed(copy) else null,
                ),
            )
        }
    }

    /** The clip on the camera, as a copy that can be deleted. */
    fun cameraCopy(media: CameraMedia) = ClipCopy(
        ClipVersion.Original, ClipCopy.Place.Camera, "On the camera", "${media.folder}/${media.name}", media.sizeBytes, ClipCopy.Ref.Camera(media),
    )

    /**
     * Deletes [items]. Copies on the phone go at once. Copies on the camera or a server need its
     * network, so they're batch jobs: the batch does first those it can on the network the phone
     * is on, and switches once for the rest. Says what happened and what was queued.
     */
    fun deleteCopies(items: List<Pair<CameraMedia, ClipCopy>>) = viewModelScope.launch {
        var deleted = 0
        val problems = mutableListOf<String>()
        val queued = mutableListOf<com.openpro.batch.BatchRepository.DeleteRequest>()
        for ((media, copy) in items) {
            try {
                when (val ref = copy.ref ?: continue) {
                    is ClipCopy.Ref.Camera -> queued += com.openpro.batch.BatchRepository.DeleteRequest(
                        ref.media, com.openpro.batch.JobVariant.Original, null, null, null, ref.media.sizeBytes,
                    )
                    is ClipCopy.Ref.Indexed -> {
                        val c = ref.copy
                        if (c.place == com.openpro.copies.CopyPlace.Server) {
                            queued += com.openpro.batch.BatchRepository.DeleteRequest(media, c.variant, c.stabilization, c.serverId, c.location, c.sizeBytes)
                        } else {
                            if (storage.deleteLocalCopy(c.place == com.openpro.copies.CopyPlace.Gallery, android.net.Uri.parse(c.location)) == false) {
                                throw java.io.IOException("Android didn't allow it; delete it in your gallery app")
                            }
                            app.copies.remove(c)
                            deleted++
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problems += "${copy.file ?: media.name} (${copy.where}): ${describeServerError(e)}"
            }
        }
        val added = if (queued.isEmpty()) 0 else batch.enqueueDeletes(queued)
        _messages.tryEmit(deleteSummary(deleted, queued, added, problems))
    }

    /** "Deleted 3 files from this phone. Added 5 deletions to the batch: 2 from the camera, now; 3 from Nextcloud, after switching to the internet." */
    private fun deleteSummary(
        deleted: Int, queued: List<com.openpro.batch.BatchRepository.DeleteRequest>, added: Int, problems: List<String>,
    ): String {
        fun files(n: Int) = if (n == 1) "1 file" else "$n files"
        val parts = mutableListOf<String>()
        if (deleted > 0) parts += "Deleted ${files(deleted)} from this phone."
        if (queued.isNotEmpty()) {
            val onCamera = camera.state.value is com.openpro.camera.CameraState.Online
            val groups = queued.groupBy { it.serverId }.map { (serverId, list) ->
                val where = if (serverId == null) "the camera" else servers.get(serverId)?.name ?: "your server"
                // The batch runs these first if the phone is already on the network they need.
                val now = (serverId == null) == onCamera
                "${list.size} from $where" + if (now) ", now" else if (serverId == null) ", once on the camera's Wi-Fi" else ", once back on the internet"
            }
            parts += if (added == 0) {
                "Those deletions are already in the batch."
            } else {
                "Added ${if (added == 1) "1 deletion" else "$added deletions"} to the batch: ${groups.joinToString("; ")}."
            }
        }
        if (problems.isNotEmpty()) parts += "Couldn't delete ${problems.size}: " + problems.joinToString("; ")
        return parts.joinToString(" ").ifEmpty { "Nothing to delete" }
    }

    /**
     * Where copies of [media] from [cam] go: by Storage's download layout, and by [server]'s upload
     * layout. Worked out when they're queued or saved, so a later camera or layout change doesn't
     * move them.
     */
    private fun placement(
        media: CameraMedia, cam: com.openpro.camera.SavedCamera? = cameraOf(media) ?: camera.current, server: com.openpro.server.ServerProfile? = null,
    ): com.openpro.batch.Placement {
        val folder = ClipNames.folderName(cam?.folderName)
        val model = cam?.model?.displayName
        return com.openpro.batch.Placement(
            saveDir = com.openpro.media.PathLayout.resolve(storage.downloadLayout.value, media, folder, model),
            uploadDir = server?.let { com.openpro.media.PathLayout.resolve(it.layout, media, folder, model) },
            cameraSerial = cam?.serial,
            saveTarget = storage.saveTarget.value.key,
        )
    }

    /** EPERM/ENONET from binding a socket to the camera Network: the handle is stale or not ours. */
    private fun Throwable.isNetworkPermissionError(): Boolean =
        generateSequence(this) { it.cause }.any { "EPERM" in (it.message ?: "") || "ENONET" in (it.message ?: "") }

    companion object {
        private const val TAG = "AppViewModel"
    }
}

/** Where a clip has copies besides the camera: on the phone, on an SD card (or one that's removed), on a server. */
data class ClipCopies(
    /** On a camera that isn't connected (as last listed). */
    val cameraAway: Boolean = false,
    val onPhone: Boolean = false,
    val onCard: Boolean = false,
    val uploaded: Boolean = false,
    /** The clip's preview is saved on the phone. */
    val preview: Boolean = false,
)

/**
 * One copy of a clip: which version, where ([where] names the place), its file name or path, its
 * size, and [ref], how to delete it (null when it can't be reached, e.g. on a removed card).
 */
data class ClipCopy(
    val version: ClipVersion, val place: Place, val where: String, val file: String?, val sizeBytes: Long?, val ref: Ref?,
) {
    sealed class Place {
        data object Camera : Place()
        data class Phone(val card: Boolean) : Place()
        data class Server(val serverId: String?) : Place()
    }

    sealed class Ref {
        /** The clip on the camera (its preview goes with it). */
        data class Camera(val media: CameraMedia) : Ref()
        /** A copy in the copies index: saved on the phone, or uploaded. */
        data class Indexed(val copy: com.openpro.copies.CopyEntity) : Ref()
    }
}
