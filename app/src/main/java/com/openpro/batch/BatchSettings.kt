package com.openpro.batch

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * When uploads happen relative to downloads from the camera. Matters because most phones can be on
 * only one WiFi network at a time: the camera's (no internet) or the normal one.
 */
enum class UploadStrategy(val label: String, val summary: String) {
    PerItem("Upload per item", "Each clip goes camera → phone → server before the next one starts."),
    Batch("Upload as a batch", "Download every queued clip first, then upload them all."),
}

enum class ConflictPolicy(val label: String, val description: String) {
    SkipIdentical("Skip if identical", "A file with the same name and size is treated as already done; a different file gets a numbered name."),
    Overwrite("Always overwrite", "Replace whatever is at the destination."),
}

/** What happens around an upload, chosen when it's queued (the Save sheet). */
data class UploadOptions(
    /** Download & upload: keep the clip on the phone too, saved like a download. */
    val keepOnPhone: Boolean = false,
    /** Once the upload is verified, delete the clip's copy on the phone. */
    val deletePhoneCopy: Boolean = false,
    /** Once every copy asked for is saved and checked, delete the clip from the camera. */
    val deleteFromCamera: Boolean = false,
)

/**
 * Where a clip's copies go, worked out from the folder layouts and its camera when it's queued (so
 * connecting another camera, or changing a layout, doesn't move queued work).
 */
data class Placement(
    val saveDir: String? = null,
    val uploadDir: String? = null,
    val cameraSerial: String? = null,
    /** Where downloads go ([com.openpro.storage.SaveTarget.key]), as set under Storage when it was queued. */
    val saveTarget: String? = null,
)

data class BatchConfig(
    val uploadStrategy: UploadStrategy = UploadStrategy.PerItem,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.SkipIdentical,
    val uploadsOverMobileData: Boolean = false,
    /**
     * The batch joins the camera's Wi-Fi to download and leaves it to upload. Off: it never changes
     * Wi-Fi; camera jobs wait until the user connects, and uploads use whatever internet there is.
     */
    val switchWifi: Boolean = true,
    val chunkSizeMb: Int = 10,
    val turnCameraWifiOffWhenDone: Boolean = false,
    val paused: Boolean = false,
)

/** Advanced batch settings, persisted in SharedPreferences. */
class BatchSettings(context: Context) {
    private val prefs = context.getSharedPreferences("batch", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<BatchConfig> = _config

    fun update(transform: (BatchConfig) -> BatchConfig) {
        val c = transform(_config.value)
        _config.value = c
        prefs.edit()
            .putString("uploadStrategy", c.uploadStrategy.name)
            .putString("conflictPolicy", c.conflictPolicy.name)
            .putBoolean("uploadsOverMobileData", c.uploadsOverMobileData)
            .putBoolean("switchWifi", c.switchWifi)
            .putInt("chunkSizeMb", c.chunkSizeMb)
            .putBoolean("turnCameraWifiOffWhenDone", c.turnCameraWifiOffWhenDone)
            .putBoolean("paused", c.paused)
            .apply()
    }

    private fun load(): BatchConfig {
        val d = BatchConfig()
        return BatchConfig(
            uploadStrategy = UploadStrategy.entries.firstOrNull { it.name == prefs.getString("uploadStrategy", null) } ?: d.uploadStrategy,
            conflictPolicy = ConflictPolicy.entries.firstOrNull { it.name == prefs.getString("conflictPolicy", null) } ?: d.conflictPolicy,
            uploadsOverMobileData = prefs.getBoolean("uploadsOverMobileData", d.uploadsOverMobileData),
            switchWifi = prefs.getBoolean("switchWifi", d.switchWifi),
            chunkSizeMb = prefs.getInt("chunkSizeMb", d.chunkSizeMb).coerceIn(1, 100),
            turnCameraWifiOffWhenDone = prefs.getBoolean("turnCameraWifiOffWhenDone", d.turnCameraWifiOffWhenDone),
            paused = prefs.getBoolean("paused", d.paused),
        )
    }
}

/**
 * The Save sheet's last choices, so it opens the way the user left it. Deleting from the camera
 * is never remembered: it starts unticked every time.
 */
class SaveChoices(context: Context) {
    private val prefs = context.getSharedPreferences("save_sheet", Context.MODE_PRIVATE)

    var toPhone: Boolean
        get() = prefs.getBoolean("toPhone", true)
        set(v) = prefs.edit().putBoolean("toPhone", v).apply()
    var toServer: Boolean
        get() = prefs.getBoolean("toServer", false)
        set(v) = prefs.edit().putBoolean("toServer", v).apply()
    var deletePhoneCopy: Boolean
        get() = prefs.getBoolean("deletePhoneCopy", false)
        set(v) = prefs.edit().putBoolean("deletePhoneCopy", v).apply()
    /** "Don't show this again" on the delete-from-camera warning. */
    var cameraDeleteWarningSeen: Boolean
        get() = prefs.getBoolean("cameraDeleteWarningSeen", false)
        set(v) = prefs.edit().putBoolean("cameraDeleteWarningSeen", v).apply()
}
