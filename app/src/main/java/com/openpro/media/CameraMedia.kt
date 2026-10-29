package com.openpro.media

/** One file in the camera's media list (gp/gpMediaList). */
data class CameraMedia(
    val folder: String,
    val name: String,
    val sizeBytes: Long,
    /** Seconds since epoch in camera-local time, as the camera reports it. */
    val createdSec: Long,
    val modifiedSec: Long,
    /** Size of the LRV low-res proxy, or -1 when the camera reports none. */
    val lrvSizeBytes: Long,
    /** Group (burst/timelapse) range from "b"/"l", or null for single files. */
    val groupFirst: Int?,
    val groupLast: Int?,
) {
    val path get() = "$folder/$name"

    /**
     * Identifies the clip. The path alone doesn't: the camera reuses names after a card is
     * formatted. Recording time and size tell such clips apart (see [ClipNames]).
     */
    val id get() = "$path@$createdSec:$sizeBytes"
    val extension get() = name.substringAfterLast('.', "").uppercase()
    val isVideo get() = extension == "MP4" || extension == "360"
    /** Photos (JPEG, or GoPro's raw GPR): saved and uploaded as they are; nothing to stabilize or preview. */
    val isPhoto get() = ClipNames.isPhotoName(name)
    val hasLrv get() = lrvSizeBytes > 0
    val lrvName get() = GoProFilenames.lowResProxyName(name)

    /** Chapter number for chaptered recordings (GX01xxxx -> 1, GX02xxxx -> 2), null otherwise. */
    val chapter: Int? get() = GoProFilenames.chapter(name)
}

/** GoPro file naming, following Quik's GpFilenameHelper (k67). */
object GoProFilenames {
    private val QUALITY_LETTERS = setOf('L', 'M', 'H', 'X')

    /**
     * GX010001.MP4 -> GL010001.LRV (quality letter swapped to 'L');
     * GOPR0001.MP4 -> GOPR0001.LRV (no quality letter, extension only).
     */
    fun lowResProxyName(name: String): String {
        val base = name.substringBeforeLast('.')
        val hasQualityLetter = base.length >= 2 && base[0] == 'G' && base[1] in QUALITY_LETTERS && !base.startsWith("GOPR")
        val proxyBase = if (hasQualityLetter) base[0] + "L" + base.substring(2) else base
        return "$proxyBase.LRV"
    }

    fun chapter(name: String): Int? {
        val base = name.substringBeforeLast('.')
        if (base.length != 8 || base.startsWith("GOPR") || base.startsWith("GP")) return null
        return base.substring(2, 4).toIntOrNull()
    }
}
