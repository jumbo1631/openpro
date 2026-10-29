package com.openpro.media

import com.openpro.quik.StabilizationChoice
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Which version of a clip a copy is. */
sealed class ClipVersion {
    /** The camera's file, as it is. */
    data object Original : ClipVersion()

    /** The camera's low-res proxy (its LRV file), saved as an MP4. */
    data object Preview : ClipVersion()

    /** Rendered with GoPro's engine, the [method] way. */
    data class Stabilized(val method: StabilizationChoice) : ClipVersion()

    /** "Original", "Preview (low-res)", "Stabilized · HyperSmooth". */
    val label: String
        get() = when (this) {
            Original -> "Original"
            Preview -> "Preview (low-res)"
            is Stabilized -> "Stabilized · " + method.shortLabel
        }
}

/**
 * GoPro file names aren't unique: the camera starts reusing them after a card is formatted, and
 * every GoPro uses the same scheme. So OpenPro identifies a clip by its name, recording time and
 * size ([CameraMedia.id]), names its own working files the same way, and names the copies it
 * saves or uploads by recording time, so they sort by when they were shot and never collide.
 */
object ClipNames {
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val PHOTO_EXTENSIONS = setOf("JPG", "JPEG", "GPR")

    fun isPhotoName(name: String) = name.substringAfterLast('.', "").uppercase() in PHOTO_EXTENSIONS

    /** MIME type of a saved copy, by its name. */
    fun mimeType(name: String): String = when (name.substringAfterLast('.', "").uppercase()) {
        "JPG", "JPEG" -> "image/jpeg"
        "GPR" -> "image/x-adobe-dng" // GoPro's raw format is DNG-based
        else -> "video/mp4"
    }

    /**
     * "2026-08-24_14-23-44": the recording time by the camera's clock (it reports local wall-clock
     * time stored as if UTC, so formatting as UTC gives what the camera showed). Null if undated.
     */
    fun stamp(media: CameraMedia): String? =
        media.createdSec.takeIf { it > 0 }?.let { STAMP.format(Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC)) }

    /**
     * Name of a saved or uploaded copy, recording time first: "2026-08-24_14-23-44_GX010849.MP4",
     * "…_GX010849_preview.mp4", "…_GX010849_stabilized-hypersmooth.mp4". Without a recording time,
     * just the rest: "GX010849_stabilized-hypersmooth.mp4".
     */
    fun saved(media: CameraMedia, version: ClipVersion): String {
        val base = cameraNamed(media, version)
        return stamp(media)?.let { "${it}_$base" } ?: base
    }

    /**
     * Every name a copy may have, [saved]'s first: copies saved before OpenPro named files by
     * recording time have the camera's name, and are still recognized.
     */
    fun candidates(media: CameraMedia, version: ClipVersion): List<String> =
        listOf(saved(media, version), cameraNamed(media, version)).distinct()

    /** The camera's name for [version]: "GX010849.MP4", "GX010849_preview.mp4", "GX010849_stabilized-level.mp4". */
    private fun cameraNamed(media: CameraMedia, version: ClipVersion): String {
        val stem = media.name.substringBeforeLast('.')
        return when (version) {
            ClipVersion.Original -> media.name
            ClipVersion.Preview -> "${stem}_preview.mp4"
            is ClipVersion.Stabilized -> "${stem}_stabilized-${version.method.slug}.mp4"
        }
    }

    /**
     * A camera's name made safe as a folder name ("HERO12 Black"), or null if nothing usable is left.
     * Characters file systems refuse become "_".
     */
    fun folderName(cameraName: String?): String? =
        cameraName?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")?.trim()?.trim('.')?.take(60)?.trim()?.ifEmpty { null }

    /** Unique name for OpenPro's working files on the phone: "100GOPRO_1786353824_GX010849.MP4". */
    fun workName(media: CameraMedia): String = "${media.folder}_${media.createdSec}_${media.name}"
}
