package com.openpro.camera

import android.content.Context
import com.openpro.camera.ble.HardwareInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A camera the user has added, identified by its [serial] number (its Bluetooth [address] can
 * change when it's paired again). Its [name] is theirs to choose; until they do ([customName] null)
 * it's the model's name and the camera's own 4-digit ID ("HERO 1234"), so two cameras of
 * the same model never share a name, or a download folder. That folder is [folderName]: the name,
 * unless the user kept the old folder when renaming ([customFolder]).
 */
@Serializable
data class SavedCamera(
    val address: String,
    /** The name the camera advertises over Bluetooth, e.g. "GoPro 1234". */
    val bleName: String,
    val serial: String? = null,
    val modelId: Int? = null,
    val firmware: String? = null,
    val customName: String? = null,
    val customFolder: String? = null,
    /** Forgotten cameras are kept, hidden, so adding one again brings back its name and folder. */
    val forgotten: Boolean = false,
    val lastConnectedAt: Long = 0,
) {
    val model: CameraModel? get() = modelId?.let { CameraModels.byModelId(it) }
    val name: String get() = customName ?: defaultName
    /** The model's name without a year ("HERO (2024)" → "HERO"), then the camera's 4-digit ID: "HERO 1234". */
    val defaultName: String
        get() = listOfNotNull(model?.displayName?.replace(Regex("""\s*\(\d{4}\)"""), "") ?: "GoPro", shortId).joinToString(" ")

    /**
     * The 4 digits the camera's Bluetooth name ends in ("GoPro 1234"), which the camera shows in its
     * connection settings; else the end of its serial number.
     */
    val shortId: String? get() = Regex("(\\d{4})\\s*$").find(bleName)?.groupValues?.get(1) ?: serial?.takeLast(4)
    val folderName: String get() = customFolder ?: name
}

/** The cameras the user has added, most recently connected first, kept in SharedPreferences. */
class CameraStore(context: Context) {
    private val prefs = context.getSharedPreferences("cameras", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val all = MutableStateFlow(load())

    private val visible = MutableStateFlow(all.value.filter { !it.forgotten })

    /** The added cameras (not the forgotten ones). */
    val cameras: StateFlow<List<SavedCamera>> = visible

    fun get(address: String?): SavedCamera? = all.value.firstOrNull { it.address == address && !it.forgotten }

    /** A camera by serial number, forgotten ones included (their clips are still in the catalogue). */
    fun bySerial(serial: String?): SavedCamera? = serial?.let { s -> all.value.firstOrNull { it.serial == s } }

    /** The camera connected most recently, for "Reconnect". */
    val last: SavedCamera? get() = cameras.value.maxByOrNull { it.lastConnectedAt }

    /**
     * Records a successful connection, recognising the camera by serial number first (so a camera
     * paired again under a new address, or forgotten and added again, is the same one). Returns it,
     * and whether it's new to the list.
     */
    fun connected(address: String, bleName: String, info: HardwareInfo): Pair<SavedCamera, Boolean> {
        val serial = info.serial.takeIf { it.isNotBlank() }
        val existing = all.value.firstOrNull { serial != null && it.serial == serial } ?: all.value.firstOrNull { it.address == address }
        val camera = (existing ?: SavedCamera(address, bleName)).copy(
            address = address,
            bleName = bleName,
            serial = serial ?: existing?.serial,
            modelId = info.modelId,
            firmware = info.firmware,
            forgotten = false,
            lastConnectedAt = System.currentTimeMillis(),
        )
        save(all.value.filter { it !== existing && it.address != address } + camera)
        return camera to (existing == null || existing.forgotten)
    }

    /**
     * Renames a camera; a blank name goes back to the model's name. With [renameFolder] its downloads
     * follow the new name; without, they stay in the folder they're in now.
     */
    fun rename(address: String, name: String, renameFolder: Boolean) = update(address) { cam ->
        cam.copy(customName = name.trim().ifEmpty { null }, customFolder = if (renameFolder) null else cam.folderName)
    }

    fun forget(address: String) = update(address) { it.copy(forgotten = true) }

    private fun update(address: String, change: (SavedCamera) -> SavedCamera) =
        save(all.value.map { if (it.address == address) change(it) else it })

    private fun save(list: List<SavedCamera>) {
        val sorted = list.sortedByDescending { it.lastConnectedAt }
        all.value = sorted
        visible.value = sorted.filter { !it.forgotten }
        prefs.edit().putString(KEY, json.encodeToString(ListSerializer(SavedCamera.serializer()), sorted)).apply()
    }

    private fun load(): List<SavedCamera> = prefs.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString(ListSerializer(SavedCamera.serializer()), it) }.getOrNull() }
        .orEmpty()

    private companion object {
        const val KEY = "list"
    }
}
