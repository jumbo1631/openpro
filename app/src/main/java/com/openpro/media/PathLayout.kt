package com.openpro.media

import java.time.Instant
import java.time.ZoneOffset

/**
 * Folder layouts for downloads and uploads: a pattern like "{camera}/{year}-{month}" turned into
 * folders for each clip, inside OpenPro's gallery folder or the server's upload folder. Changing
 * a layout only affects new copies; OpenPro finds existing ones through its copies index.
 */
object PathLayout {
    const val CAMERA = "{camera}"
    const val MODEL = "{model}"
    const val YEAR = "{year}"
    const val MONTH = "{month}"
    const val DAY = "{day}"

    val tokens = listOf(CAMERA, MODEL, YEAR, MONTH, DAY)

    /** Named layouts offered as choices; any pattern can be typed too. */
    data class Preset(val label: String, val pattern: String)

    val downloadPresets = listOf(
        Preset("By camera", CAMERA),
        Preset("By camera and month", "$CAMERA/$YEAR-$MONTH"),
        Preset("By month", "$YEAR-$MONTH"),
        Preset("One folder", ""),
    )

    val uploadPresets = listOf(
        Preset("By month", "$YEAR-$MONTH"),
        Preset("By camera and month", "$CAMERA/$YEAR-$MONTH"),
        Preset("By camera", CAMERA),
        Preset("One folder", ""),
    )

    /**
     * The folders for [media] under [pattern] ("HERO12 Black/2026-08"), or null for none. [camera]
     * is the camera's folder name and [model] its model's name. Each folder is made safe for file
     * systems; one that names a date for an undated clip becomes "undated", and one that comes out
     * empty is left out.
     */
    fun resolve(pattern: String, media: CameraMedia, camera: String?, model: String?): String? {
        val date = media.createdSec.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC) }
        val folders = pattern.split('/').mapNotNull { segment ->
            if (date == null && (YEAR in segment || MONTH in segment || DAY in segment)) return@mapNotNull "undated"
            val text = segment
                .replace(CAMERA, camera ?: "Unknown camera")
                .replace(MODEL, model ?: "Unknown model")
                .replace(YEAR, date?.year?.toString().orEmpty())
                .replace(MONTH, date?.monthValue?.let { "%02d".format(it) }.orEmpty())
                .replace(DAY, date?.dayOfMonth?.let { "%02d".format(it) }.orEmpty())
            ClipNames.folderName(text)
        }
        return folders.joinToString("/").ifEmpty { null }
    }

    /** Whether [pattern] puts each camera's clips apart, so renaming a camera matters to it. */
    fun usesCamera(pattern: String) = CAMERA in pattern
}
