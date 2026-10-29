package com.openpro.storage

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

/** A storage device the phone knows about: its own storage, an SD card, a USB drive. */
data class Volume(
    /** "internal" for the phone's own storage, otherwise the volume's UUID (e.g. "1A2B-3C4D"). */
    val id: String,
    /** What Android calls it, e.g. "Internal shared storage", "SanDisk SD card". */
    val label: String,
    val removable: Boolean,
    val mounted: Boolean,
    /** Root of the volume, for capacity (null while unmounted). */
    val root: File?,
    /** OpenPro's own folder on it (Android/data/…/files/Movies); null if apps can't have one here. */
    val appDir: File?,
    /** MediaStore volume name, for saving to its gallery (null if it has none). */
    val mediaStoreName: String?,
) {
    val isInternal get() = id == INTERNAL
    /** Can hold working files (downloads in progress, staging, renders). */
    val canHoldWork get() = mounted && appDir != null
    /** Has a gallery OpenPro can save into. */
    val hasGallery get() = mounted && mediaStoreName != null

    val shortLabel get() = if (isInternal) "Phone" else label

    companion object {
        const val INTERNAL = "internal"
    }
}

/**
 * Tracks mounted storage volumes and reports changes (an SD card inserted or removed) through
 * [volumes]. Only app-specific folders and MediaStore are used here, so no storage permission is
 * needed; folders on other devices (USB drives) go through a folder the user picks (see Storage).
 */
class Volumes(private val context: Context) {
    private val manager = context.getSystemService(StorageManager::class.java)
    private val _volumes = MutableStateFlow(read())
    val volumes: StateFlow<List<Volume>> = _volumes
    /** Labels of volumes seen before, so a removed card can still be named. */
    private val prefs = context.getSharedPreferences("volumes", Context.MODE_PRIVATE)

    /** Called after every change, e.g. to let waiting batch jobs re-check. */
    var onChange: (() -> Unit)? = null

    init {
        remember(_volumes.value)
        manager.registerStorageVolumeCallback(Executors.newSingleThreadExecutor(), object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) {
                Log.i(TAG, "volume ${volume.uuid ?: "primary"} is now ${volume.state}")
                refresh()
            }
        })
    }

    fun refresh() {
        val now = read()
        remember(now)
        _volumes.value = now
        onChange?.invoke()
    }

    fun byId(id: String): Volume? = _volumes.value.firstOrNull { it.id == id }

    /** A volume's name even when it isn't inserted. */
    fun labelOf(id: String): String =
        byId(id)?.label ?: prefs.getString("label:$id", null) ?: if (id == Volume.INTERNAL) "Phone" else "Storage device $id"

    /** The volume holding [file], if any. */
    fun volumeOf(file: File): Volume? {
        val v = runCatching { manager.getStorageVolume(file) }.getOrNull() ?: return null
        return byId(v.uuid ?: Volume.INTERNAL)
    }

    private fun remember(list: List<Volume>) {
        val edit = prefs.edit()
        list.forEach { edit.putString("label:${it.id}", it.label) }
        edit.apply()
    }

    private fun read(): List<Volume> {
        // getExternalFilesDirs creates and returns OpenPro's folder on every volume that allows one.
        val appDirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull()
        val dirByVolume = appDirs.mapNotNull { dir ->
            runCatching { manager.getStorageVolume(dir) }.getOrNull()?.let { (it.uuid ?: Volume.INTERNAL) to dir }
        }.toMap()
        return manager.storageVolumes.map { v ->
            val id = v.uuid ?: Volume.INTERNAL
            val mounted = v.state == Environment.MEDIA_MOUNTED
            Volume(
                id = id,
                label = v.getDescription(context),
                removable = v.isRemovable,
                mounted = mounted,
                root = v.directory,
                appDir = dirByVolume[id]?.takeIf { mounted },
                mediaStoreName = v.mediaStoreVolumeName?.takeIf { mounted },
            )
        }.sortedBy { if (it.isInternal) 0 else 1 }
    }

    companion object {
        private const val TAG = "Volumes"
    }
}
