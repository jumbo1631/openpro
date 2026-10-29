package com.openpro.copies

import com.openpro.media.ClipNames
import com.openpro.media.ClipVersion
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * How safe a clip is, by its best full-quality copy (the original, or a stabilized version; a
 * preview doesn't count): on a server, only on this phone (or its card or a picked folder), or
 * nowhere but the camera.
 */
enum class Safety { BackedUp, PhoneOnly, NotSaved }

/** Counts and sizes for one group of clips (all of them, one month, one camera). */
data class SafetyCounts(val backedUp: Int = 0, val phoneOnly: Int = 0, val notSaved: Int = 0) {
    val total get() = backedUp + phoneOnly + notSaved
    operator fun get(s: Safety) = when (s) {
        Safety.BackedUp -> backedUp
        Safety.PhoneOnly -> phoneOnly
        Safety.NotSaved -> notSaved
    }

    fun plus(s: Safety) = when (s) {
        Safety.BackedUp -> copy(backedUp = backedUp + 1)
        Safety.PhoneOnly -> copy(phoneOnly = phoneOnly + 1)
        Safety.NotSaved -> copy(notSaved = notSaved + 1)
    }
}

/** A set of files and how much of it is each version: [count] files (a preview beside its clip on a camera isn't one). */
data class Files(val count: Int = 0, val original: Long = 0, val stabilized: Long = 0, val preview: Long = 0) {
    val bytes get() = original + stabilized + preview

    fun plus(version: ClipVersion, size: Long, file: Boolean = true): Files {
        val n = if (file) count + 1 else count
        return when (version) {
            ClipVersion.Original -> copy(count = n, original = original + size)
            ClipVersion.Preview -> copy(count = n, preview = preview + size)
            is ClipVersion.Stabilized -> copy(count = n, stabilized = stabilized + size)
        }
    }
}

/**
 * Everything the stats page shows, worked out from the clips OpenPro knows and the copies it has
 * saved or uploaded. Sizes are the camera's (originals), and each copy's own for space used.
 */
data class MediaStats(
    val videos: Int,
    val photos: Int,
    /** Size of the originals of every known clip. */
    val footageBytes: Long,
    val safety: SafetyCounts,
    /** Size of the originals in each safety group. */
    val safetyBytes: Map<Safety, Long>,
    /** Clips recorded each month, oldest first; undated clips aren't in it. */
    val byMonth: List<Pair<YearMonth, SafetyCounts>>,
    /** Clips per camera serial (null: a camera OpenPro didn't record), most first. */
    val byCamera: List<Pair<String?, SafetyCounts>>,
    /** What's on each camera's card by serial (its files and their previews), most first. */
    val onCards: List<Pair<String?, Files>>,
    /** Everything OpenPro has saved on this phone, its card and picked folders. */
    val phone: Files,
    /** What OpenPro has uploaded to each server, by server id, most first. */
    val byServer: List<Pair<String?, Files>>,
) {
    val clips get() = videos + photos

    companion object {
        fun safetyOf(copies: List<CopyEntity>): Safety {
            val full = copies.filter { it.version != ClipVersion.Preview }
            return when {
                full.any { it.place == CopyPlace.Server } -> Safety.BackedUp
                full.any { it.isLocal } -> Safety.PhoneOnly
                else -> Safety.NotSaved
            }
        }

        fun of(clips: Collection<ClipEntity>, copies: Map<String, List<CopyEntity>>): MediaStats {
            var safety = SafetyCounts()
            val safetyBytes = mutableMapOf<Safety, Long>()
            val months = sortedMapOf<YearMonth, SafetyCounts>()
            val cameras = mutableMapOf<String?, SafetyCounts>()
            var videos = 0
            var photos = 0
            for (clip in clips) {
                val s = safetyOf(copies[clip.id].orEmpty())
                safety = safety.plus(s)
                safetyBytes.merge(s, clip.sizeBytes, Long::plus)
                if (ClipNames.isPhotoName(clip.name)) photos++ else videos++
                if (clip.createdSec > 0) {
                    // The camera's clock, stored as if UTC (see ClipNames.stamp).
                    val month = YearMonth.from(LocalDateTime.ofEpochSecond(clip.createdSec, 0, ZoneOffset.UTC))
                    months[month] = (months[month] ?: SafetyCounts()).plus(s)
                }
                cameras[clip.cameraSerial] = (cameras[clip.cameraSerial] ?: SafetyCounts()).plus(s)
            }
            val all = copies.values.flatten()
            fun files(list: List<CopyEntity>) = list.fold(Files()) { f, c -> f.plus(c.version, c.sizeBytes) }
            return MediaStats(
                videos = videos,
                photos = photos,
                footageBytes = clips.sumOf { it.sizeBytes },
                safety = safety,
                safetyBytes = safetyBytes,
                byMonth = months.toList(),
                byCamera = cameras.toList().sortedByDescending { it.second.total },
                onCards = clips.filter { it.onCamera }.groupBy { it.cameraSerial }
                    .map { (serial, list) ->
                        serial to list.fold(Files()) { f, c ->
                            f.plus(ClipVersion.Original, c.sizeBytes).let { if (c.lrvSizeBytes > 0) it.plus(ClipVersion.Preview, c.lrvSizeBytes, file = false) else it }
                        }
                    }
                    .sortedByDescending { it.second.bytes },
                phone = files(all.filter { it.isLocal }),
                byServer = all.filter { it.place == CopyPlace.Server }.groupBy { it.serverId }
                    .map { (id, list) -> id to files(list) }
                    .sortedByDescending { it.second.bytes },
            )
        }
    }
}
