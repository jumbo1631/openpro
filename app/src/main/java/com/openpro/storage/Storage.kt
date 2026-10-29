package com.openpro.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.openpro.media.CameraMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A storage location that is needed but not available right now (card removed, folder access lost). */
class StorageUnavailableException(message: String) : IOException(message)

/** Where downloads (and stabilized versions) are saved. */
sealed interface SaveTarget {
    /** The gallery (Movies/OpenPro, and Pictures/OpenPro for photos) on a volume: the phone's own storage or an SD card. */
    data class Gallery(val volumeId: String) : SaveTarget

    /** A folder the user picked through Android's file picker: any device, including USB drives. */
    data class Folder(val treeUri: String, val label: String) : SaveTarget

    /** This target as text, kept with a batch job so it saves where it was queued to save. */
    val key: String
        get() = when (this) {
            is Gallery -> "gallery:$volumeId"
            is Folder -> "folder:$treeUri\n$label"
        }

    companion object {
        fun parse(key: String): SaveTarget? = when {
            key.startsWith("gallery:") -> Gallery(key.removePrefix("gallery:"))
            key.startsWith("folder:") -> key.removePrefix("folder:").split('\n', limit = 2).let { Folder(it[0], it.getOrElse(1) { "Chosen folder" }) }
            else -> null
        }
    }
}

/**
 * Where things live:
 *  - previews and thumbnails: the phone's cache, size-capped (Android may also clear it)
 *  - working files (downloaded originals kept in OpenPro, upload staging, renders): OpenPro's own
 *    folder on the chosen *work volume*, the phone or an SD card
 *  - finished videos: the chosen [SaveTarget], the gallery on the phone or SD card, or any folder
 * Files saved earlier on another volume are still found while that volume is present; when it
 * isn't, [missingOriginal] names it so the UI can say so instead of showing a broken file.
 */
class Storage(private val context: Context) {
    private val prefs = context.getSharedPreferences("storage", Context.MODE_PRIVATE)
    val volumes = Volumes(context)

    /** The device chosen for working files under Advanced, or null to follow downloads (see [workVolumeId]). */
    private val _workChoice = MutableStateFlow(prefs.getString("workVolume", null)?.takeIf { it != Volume.INTERNAL })
    val workChoice: StateFlow<String?> = _workChoice
    private val _saveTarget = MutableStateFlow(loadSaveTarget())
    val saveTarget: StateFlow<SaveTarget> = _saveTarget

    /** Gallery thumbnails' quality, or null for none (placeholders, nothing fetched). */
    private val _thumbQuality = MutableStateFlow(
        prefs.getString("thumbQuality", null).let { name ->
            if (name == "None") null else com.openpro.media.ThumbQuality.entries.firstOrNull { it.name == name } ?: com.openpro.media.ThumbQuality.Low
        },
    )
    val thumbQuality: StateFlow<com.openpro.media.ThumbQuality?> = _thumbQuality

    fun setThumbQuality(quality: com.openpro.media.ThumbQuality?) {
        prefs.edit().putString("thumbQuality", quality?.name ?: "None").apply()
        _thumbQuality.value = quality
    }

    /** Which qualities of thumbnail are kept on the phone (both, to start with). */
    private val _keptThumbs = MutableStateFlow(
        com.openpro.media.ThumbQuality.entries.filter { prefs.getBoolean("keepThumbs.${it.name}", true) }.toSet(),
    )
    val keptThumbs: StateFlow<Set<com.openpro.media.ThumbQuality>> = _keptThumbs

    /** Keeps thumbnails of [quality] or not; not keeping them deletes those already kept. */
    fun setKeepThumbs(quality: com.openpro.media.ThumbQuality, keep: Boolean) {
        prefs.edit().putBoolean("keepThumbs.${quality.name}", keep).apply()
        _keptThumbs.value = if (keep) _keptThumbs.value + quality else _keptThumbs.value - quality
        if (!keep) thumbDir(quality).deleteRecursively()
    }

    /** Where kept thumbnails of [quality] are. */
    fun thumbDir(quality: com.openpro.media.ThumbQuality) = File(context.cacheDir, "thumbs/${quality.name.lowercase()}")

    fun thumbBytes(quality: com.openpro.media.ThumbQuality): Long = thumbDir(quality).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** How downloads are arranged in folders inside OpenPro's (see [com.openpro.media.PathLayout]). */
    private val _downloadLayout = MutableStateFlow(prefs.getString("downloadLayout", null) ?: com.openpro.media.PathLayout.CAMERA)
    val downloadLayout: StateFlow<String> = _downloadLayout

    fun setDownloadLayout(pattern: String) {
        prefs.edit().putString("downloadLayout", pattern).apply()
        _downloadLayout.value = pattern
    }

    /** Chooses the device for working files; null follows the device downloads go to. */
    fun setWorkVolume(id: String?) {
        prefs.edit().putString("workVolume", id).apply()
        _workChoice.value = id
    }

    /**
     * Where working files go: the device chosen under Advanced, else the device downloads go to
     * (so a download is moved, not copied, into place), or the phone when that device can't hold
     * OpenPro's files (a USB drive).
     */
    val workVolumeId: String
        get() = _workChoice.value ?: autoWorkVolume()

    private fun autoWorkVolume(): String {
        val id = when (val t = saveTarget.value) {
            is SaveTarget.Gallery -> t.volumeId
            is SaveTarget.Folder -> folderVolumeId(t)
        }
        return id?.takeIf { volumes.byId(it)?.canHoldWork == true } ?: Volume.INTERNAL
    }

    fun setSaveTarget(target: SaveTarget) {
        val edit = prefs.edit()
        when (target) {
            is SaveTarget.Gallery -> edit.putString("saveKind", "gallery").putString("saveVolume", target.volumeId)
            is SaveTarget.Folder -> edit.putString("saveKind", "folder").putString("saveTree", target.treeUri).putString("saveLabel", target.label)
        }
        edit.apply()
        _saveTarget.value = target
    }

    /** Keeps access to a folder picked with ACTION_OPEN_DOCUMENT_TREE across restarts, and saves there. */
    fun useFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val label = DocumentFile.fromTreeUri(context, treeUri)?.name ?: "Chosen folder"
        // Remembered, so it stays on the list after choosing somewhere else.
        prefs.edit().putString("lastTree", treeUri.toString()).putString("lastLabel", label).apply()
        setSaveTarget(SaveTarget.Folder(treeUri.toString(), label))
    }

    /**
     * Whether this app may still write to a picked folder. Android gives folder access to the app that
     * picked it, so a folder that came over in imported settings has none until it's picked again here.
     */
    fun hasAccess(folder: SaveTarget.Folder): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri.toString() == folder.treeUri && it.isWritePermission }

    /** Where Android's folder picker should open to pick [folder] again: the folder itself. */
    fun pickerStart(folder: SaveTarget.Folder): Uri? = runCatching {
        val tree = Uri.parse(folder.treeUri)
        android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
    }.getOrNull()

    private fun loadSaveTarget(): SaveTarget = when (prefs.getString("saveKind", "gallery")) {
        "folder" -> SaveTarget.Folder(prefs.getString("saveTree", "")!!, prefs.getString("saveLabel", "Chosen folder")!!)
        else -> SaveTarget.Gallery(prefs.getString("saveVolume", Volume.INTERNAL)!!)
    }

    // --- working files ---

    /** The work volume, or why it can't be used right now. */
    fun workVolume(): Volume {
        val id = workVolumeId
        val v = volumes.byId(id)
        if (v == null || !v.canHoldWork) {
            throw StorageUnavailableException(
                "${volumes.labelOf(id)} isn't available for OpenPro's working files. Insert it, or choose another " +
                    "location under Storage.",
            )
        }
        return v
    }

    private fun workDir(name: String): File = File(workVolume().appDir, name).apply { mkdirs() }

    val previewDir: File get() = File(context.cacheDir, "previews").apply { mkdirs() }
    val originalsDir: File get() = workDir("originals")
    val renderDir: File get() = workDir("render")
    val stagingDir: File get() = workDir("staging")

    /** Bytes free on the work volume (where downloads, staging and renders go). */
    fun freeBytes(): Long = workVolume().appDir!!.usableSpace

    fun stagingFile(media: CameraMedia) = File(stagingDir, com.openpro.media.ClipNames.workName(media))

    /** A copy of a clip saved in a picked folder, made for the stabilizing engine (which needs a file it can open by path). */
    fun engineSourceFile(media: CameraMedia): File =
        com.openpro.media.ClipNames.workName(media).let { File(stagingDir, it.substringBeforeLast('.') + "_source." + it.substringAfterLast('.')) }

    /** Where a clip's preview (LRV) is downloaded to before it's saved or uploaded. */
    fun previewStagingFile(media: CameraMedia) =
        File(stagingDir, com.openpro.media.ClipNames.workName(media).substringBeforeLast('.') + "_preview.mp4")

    /** Where a new download of [media] goes (on the work volume). */
    fun originalFile(media: CameraMedia) = File(originalsDir, originalName(media))

    private fun originalName(media: CameraMedia) = com.openpro.media.ClipNames.workName(media)

    /** A downloaded original kept by OpenPro, on whichever volume it was saved to, if present. */
    fun localOriginal(media: CameraMedia): File? =
        volumes.volumes.value.mapNotNull { it.appDir?.let { dir -> File(File(dir, "originals"), originalName(media)) } }.firstOrNull { it.exists() }

    /** Records which volume a finished download is on, so its absence can be explained later. */
    fun recordOriginal(media: CameraMedia, file: File) {
        val id = volumes.volumeOf(file)?.id ?: return
        prefs.edit().putString("orig:${originalName(media)}", id).apply()
    }

    fun forgetOriginal(media: CameraMedia) = prefs.edit().remove("orig:${originalName(media)}").apply()

    /** Name of the volume holding this clip's downloaded original when that volume isn't present. */
    fun missingOriginal(media: CameraMedia): String? {
        val id = prefs.getString("orig:${originalName(media)}", null) ?: return null
        if (localOriginal(media) != null) return null
        val v = volumes.byId(id)
        if (v != null && v.mounted) {
            // Its volume is here but the file isn't: it was deleted, not unplugged.
            forgetOriginal(media)
            return null
        }
        return volumes.labelOf(id)
    }

    fun renderFile(media: CameraMedia) = File(renderDir, com.openpro.media.ClipNames.workName(media).substringBeforeLast('.') + "_stabilized.mp4")

    /** Stabilized previews from earlier versions, which rendered them on the clip page. */
    fun clearPreviews() = previewDir.listFiles().orEmpty().forEach { it.delete() }

    // --- finished videos ---

    /** Short description of where finished videos go, e.g. "Gallery on SanDisk SD card". */
    /** Where [target] is, as a device and folder: "Phone · Movies/OpenPro", "Phone · GoPro" ([photo]s go in Pictures/OpenPro). */
    fun saveTargetLabel(target: SaveTarget = saveTarget.value, photo: Boolean = false): String = when (target) {
        is SaveTarget.Gallery -> "${deviceLabel(target.volumeId)} · ${if (photo) PHOTO_PATH else GALLERY_PATH}"
        is SaveTarget.Folder -> "${deviceLabel(folderVolumeId(target))} · ${folderPath(target)}"
    }

    /** The folder picked most recently, if any: offered alongside the galleries even when not in use. */
    fun pickedFolder(): SaveTarget.Folder? =
        (saveTarget.value as? SaveTarget.Folder) ?: prefs.getString("lastTree", null)?.let { SaveTarget.Folder(it, prefs.getString("lastLabel", null) ?: "Chosen folder") }

    /** What people call a device: "Phone", or Android's name for it ("SanDisk SD card", "USB drive"). */
    fun deviceLabel(volumeId: String?): String = when (volumeId) {
        null -> "Storage device"
        Volume.INTERNAL -> "Phone"
        else -> volumes.labelOf(volumeId)
    }

    /** The device a picked folder is on, from its link ("primary:GoPro" is on the phone's own storage). */
    fun folderVolumeId(t: SaveTarget.Folder): String? = treeDocumentId(t)?.substringBefore(':')?.let { if (it == "primary") Volume.INTERNAL else it }

    private fun folderVolume(t: SaveTarget.Folder): Volume? = folderVolumeId(t)?.let(volumes::byId)

    /** A picked folder's path on its device ("GoPro", "DCIM/GoPro"), or its name if Android doesn't say. */
    fun folderPath(t: SaveTarget.Folder): String = treeDocumentId(t)?.substringAfter(':', "")?.ifEmpty { null } ?: t.label

    private fun treeDocumentId(t: SaveTarget.Folder): String? =
        runCatching { android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(t.treeUri)) }.getOrNull()

    /** The target a batch job was queued with ([SaveTarget.key]), or today's for jobs queued before that was kept. */
    fun targetOf(key: String?): SaveTarget = key?.let(SaveTarget::parse) ?: saveTarget.value

    /** Throws [StorageUnavailableException] if finished videos can't be saved to [target] right now. */
    fun checkSaveTarget(target: SaveTarget = saveTarget.value) {
        when (val t = target) {
            is SaveTarget.Gallery -> galleryVolumeName(t)
            is SaveTarget.Folder -> folder(t)
        }
    }

    /** Free bytes where finished videos go, when Android can tell (not for picked folders). */
    fun saveTargetFreeBytes(target: SaveTarget = saveTarget.value): Long? = when (val t = target) {
        is SaveTarget.Gallery -> volumes.byId(t.volumeId)?.root?.usableSpace
        is SaveTarget.Folder -> null
    }

    private fun galleryVolumeName(t: SaveTarget.Gallery): String {
        val v = volumes.byId(t.volumeId)
        return v?.mediaStoreName?.takeIf { v.hasGallery }
            ?: throw StorageUnavailableException("${volumes.labelOf(t.volumeId)} isn't inserted. Insert it, or choose where to save under Storage.")
    }

    private fun folder(t: SaveTarget.Folder): DocumentFile {
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(t.treeUri))
        if (dir == null || !dir.exists() || !dir.canWrite()) {
            throw StorageUnavailableException(
                "Can't reach the folder \"${t.label}\" (drive unplugged, or access removed). Reconnect it, or choose " +
                    "where to save under Storage.",
            )
        }
        return dir
    }

    /**
     * Where in a gallery volume a copy named [displayName] goes: photos are images in
     * Pictures/OpenPro, everything else videos in Movies/OpenPro (Android files each kind only
     * in its own collection).
     */
    private fun galleryPlace(displayName: String, volume: String): Pair<Uri, String> =
        if (com.openpro.media.ClipNames.isPhotoName(displayName)) MediaStore.Images.Media.getContentUri(volume) to PHOTO_PATH
        else MediaStore.Video.Media.getContentUri(volume) to GALLERY_PATH

    /** True if [target] is on a removable card or drive. */
    fun savesToRemovable(target: SaveTarget = saveTarget.value): Boolean = when (target) {
        is SaveTarget.Gallery -> volumes.byId(target.volumeId)?.isInternal == false
        is SaveTarget.Folder -> folderVolume(target)?.isInternal == false
    }

    /** A file just saved where finished files go: what the copies index records about it. */
    data class Saved(val uri: Uri, val fileName: String, val sizeBytes: Long, val inGallery: Boolean, val label: String, val onRemovable: Boolean)

    /**
     * Copies a finished file to where finished files go, into [subfolder] (one or more folders,
     * "HERO12 Black/2026-08") inside OpenPro's, and deletes the private copy. With [overwrite], an
     * existing file of the same name there is replaced; otherwise both are kept and the new one gets
     * a numbered name.
     */
    suspend fun publishToGallery(
        file: File, displayName: String, overwrite: Boolean = false, subfolder: String? = null, target: SaveTarget = saveTarget.value,
    ): Saved = withContext(Dispatchers.IO) {
        val size = file.length()
        val (uri, name) = when (target) {
            is SaveTarget.Gallery -> publishToMediaStore(file, displayName, overwrite, galleryVolumeName(target), subfolder)
            is SaveTarget.Folder -> publishToFolder(file, displayName, overwrite, subfolder?.let { subdirs(folder(target), it) } ?: folder(target))
        }
        file.delete()
        Saved(uri, name, size, target is SaveTarget.Gallery, saveTargetLabel(target, com.openpro.media.ClipNames.isPhotoName(displayName)) + (subfolder?.let { "/$it" } ?: ""), savesToRemovable(target))
    }

    /** Folders inside a picked folder ("a/b"), created where missing. */
    private fun subdirs(root: DocumentFile, path: String): DocumentFile =
        path.split('/').filter { it.isNotEmpty() }.fold(root) { dir, name ->
            dir.findFile(name)?.takeIf { it.isDirectory } ?: dir.createDirectory(name)
                ?: throw IOException("could not create the folder \"$name\" in \"${dir.name}\"")
        }

    /** Saves into the gallery; returns the new entry and its name (Android may number it to avoid a clash). */
    private fun publishToMediaStore(file: File, displayName: String, overwrite: Boolean, volume: String, subfolder: String?): Pair<Uri, String> {
        val resolver = context.contentResolver
        val (collection, base) = galleryPlace(displayName, volume)
        val path = if (subfolder != null) "$base/$subfolder" else base
        if (overwrite) {
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(displayName, "$path/"),
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, com.openpro.media.ClipNames.mimeType(displayName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("could not create gallery entry on $volume")
        try {
            copyInto(file, uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: displayName
        return uri to name
    }

    private fun publishToFolder(file: File, displayName: String, overwrite: Boolean, dir: DocumentFile): Pair<Uri, String> {
        if (overwrite) dir.findFile(displayName)?.delete()
        // Written under a temporary name and renamed when complete, so a pulled drive never leaves
        // a truncated file that looks finished.
        val partial = dir.createFile("application/octet-stream", "$displayName.part")
            ?: throw IOException("could not create a file in \"${dir.name}\"")
        try {
            copyInto(file, partial.uri)
            val finalName = if (!overwrite && dir.findFile(displayName) != null) numbered(dir, displayName) else displayName
            // renameTo updates the DocumentFile to the renamed document.
            if (!partial.renameTo(finalName)) throw IOException("could not finish $finalName in \"${dir.name}\"")
            return partial.uri to finalName
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    /**
     * A gallery copy as a file OpenPro can read, if it's [size] bytes (when known): uploads and
     * stabilizing use it instead of fetching from the camera. Android lets an app read the gallery
     * files it saved itself by path.
     */
    fun galleryFile(uri: Uri, size: Long): File? = runCatching {
        val (relative, name) = context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null } ?: return null
        val volume = MediaStore.getVolumeName(uri)
        val root = volumes.volumes.value.firstOrNull { it.mediaStoreName == volume }?.root ?: return null
        File(File(root, relative), name).takeIf { it.isFile && (size <= 0 || it.length() == size) && it.canRead() }
    }.getOrNull()

    /**
     * Of these gallery copies ([uris], MediaStore links), the ones definitely gone: asked of Android
     * a few hundred at a time rather than one by one. Copies on a card that isn't present aren't gone.
     */
    fun goneFromGallery(uris: List<Uri>): Set<Uri> {
        val present = MediaStore.getExternalVolumeNames(context)
        val gone = mutableSetOf<Uri>()
        for ((volume, onVolume) in uris.groupBy { runCatching { MediaStore.getVolumeName(it) }.getOrNull() }) {
            if (volume == null || volume !in present) continue
            val byId = onVolume.mapNotNull { uri -> runCatching { android.content.ContentUris.parseId(uri) }.getOrNull()?.let { it to uri } }.toMap()
            for (chunk in byId.keys.chunked(500)) {
                val found = mutableSetOf<Long>()
                val ok = runCatching {
                    context.contentResolver.query(
                        MediaStore.Files.getContentUri(volume), arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",")})", null, null,
                    )?.use { c -> while (c.moveToNext()) found += c.getLong(0) }
                }.isSuccess
                if (ok) chunk.filter { it !in found }.forEach { gone += byId.getValue(it) }
            }
        }
        return gone
    }

    /**
     * True if a saved copy is definitely gone: its gallery entry or document no longer exists. A copy
     * on a card or drive that isn't present counts as still there.
     */
    fun localCopyGone(inGallery: Boolean, uri: Uri): Boolean = runCatching {
        if (inGallery) {
            if (MediaStore.getVolumeName(uri) !in MediaStore.getExternalVolumeNames(context)) return false
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count == 0 } ?: false
        } else {
            if (DocumentFile.fromSingleUri(context, uri)?.exists() != false) return false
            // Missing: gone only if the picked folder it was saved in is reachable; otherwise its drive is just unplugged.
            val tree = context.contentResolver.persistedUriPermissions.map { it.uri }.firstOrNull { uri.toString().startsWith(it.toString()) }
            tree != null && DocumentFile.fromTreeUri(context, tree)?.exists() == true
        }
    }.getOrDefault(false)

    /**
     * Deletes a saved copy. Null if it was already gone; false if Android refused (a file OpenPro
     * didn't save itself, e.g. from before a reinstall).
     */
    suspend fun deleteLocalCopy(inGallery: Boolean, uri: Uri): Boolean? = withContext(Dispatchers.IO) {
        try {
            if (inGallery) {
                if (context.contentResolver.delete(uri, null, null) > 0) true else null
            } else {
                if (android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)) true else null
            }
        } catch (e: SecurityException) {
            false
        } catch (e: java.io.FileNotFoundException) {
            null
        }
    }

    private fun numbered(dir: DocumentFile, name: String): String {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        return (1..999).map { "$base ($it)" + if (ext.isNotEmpty()) ".$ext" else "" }.first { dir.findFile(it) == null }
    }

    private fun copyInto(file: File, uri: Uri) {
        val out = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("could not write $uri")
        out.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 20) } }
    }

    // --- usage breakdown ---

    data class Usage(
        val volume: Volume,
        val total: Long,
        val free: Long,
        val originals: Long,
        val working: Long,
        val gallery: Long,
        val cache: Long,
    ) {
        val openPro get() = originals + working + gallery + cache
    }

    /** OpenPro's usage on each volume (gallery = Movies/OpenPro and Pictures/OpenPro there). */
    suspend fun usage(): List<Usage> = withContext(Dispatchers.IO) {
        volumes.volumes.value.filter { it.mounted }.map { v ->
            val app = v.appDir
            fun size(name: String) = app?.let { File(it, name).walkBottomUp().filter { f -> f.isFile }.sumOf { f -> f.length() } } ?: 0L
            Usage(
                volume = v,
                total = v.root?.totalSpace ?: 0,
                free = v.root?.usableSpace ?: 0,
                originals = size("originals"),
                working = size("staging") + size("render"),
                gallery = v.mediaStoreName?.let { gallerySize(it) } ?: 0,
                cache = if (v.isInternal) context.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0,
            )
        }
    }

    private fun gallerySize(volume: String): Long =
        listOf(MediaStore.Video.Media.getContentUri(volume) to GALLERY_PATH, MediaStore.Images.Media.getContentUri(volume) to PHOTO_PATH)
            .sumOf { (collection, path) ->
                runCatching {
                    context.contentResolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns.SIZE),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf("$path/%"),
                        null,
                    )?.use { c -> var sum = 0L; while (c.moveToNext()) sum += c.getLong(0); sum } ?: 0L
                }.getOrDefault(0L)
            }

    /** Files and bytes OpenPro saved in the picked folder (null if none is picked or reachable). */
    /** What's saved in the picked folder: where it is ("Phone · GoPro") and how much. */
    suspend fun folderUsage(): Pair<String, Long>? = withContext(Dispatchers.IO) {
        val t = pickedFolder() ?: return@withContext null
        val dir = runCatching { folder(t) }.getOrNull() ?: return@withContext null
        fun size(d: DocumentFile): Long = d.listFiles().sumOf { if (it.isDirectory) size(it) else it.length() }
        saveTargetLabel(t) to size(dir)
    }

    companion object {
        val GALLERY_PATH = Environment.DIRECTORY_MOVIES + "/OpenPro"
        /** Photos go here: Android keeps images out of Movies. */
        val PHOTO_PATH = Environment.DIRECTORY_PICTURES + "/OpenPro"
    }
}
