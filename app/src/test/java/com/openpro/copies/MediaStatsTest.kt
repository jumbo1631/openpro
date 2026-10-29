package com.openpro.copies

import com.openpro.batch.JobVariant
import com.openpro.media.CameraMedia
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth

class MediaStatsTest {
    // 1_786_353_824 is 2026-08-10 by the camera's clock; 1_788_000_000 is 2026-08-29; 1_790_000_000 is 2026-09-21.
    private fun media(name: String, at: Long, size: Long = 1_000) = CameraMedia("100GOPRO", name, size, at, 0, 100, null, null)

    private fun copy(m: CameraMedia, place: CopyPlace, variant: JobVariant = JobVariant.Original, server: String? = "s1") = CopyEntity(
        id = m.name + place + variant, clipId = m.id, variant = variant, stabilization = null, place = place, location = "x",
        serverId = server.takeIf { place == CopyPlace.Server }, fileName = m.name, placeLabel = "", onRemovable = false,
        sizeBytes = 500, sha1 = null, cameraSerial = null, savedAt = 0,
    )

    private val uploaded = media("GX010001.MP4", 1_786_353_824)
    private val onPhone = media("GX010002.MP4", 1_788_000_000)
    private val previewOnly = media("GX010003.MP4", 1_790_000_000)
    private val photo = media("GOPR0004.JPG", 0, size = 200)

    private val clips = listOf(
        ClipEntity.of(uploaded, "A", 0),
        ClipEntity.of(onPhone, "A", 0),
        ClipEntity.of(previewOnly, "B", 0).copy(onCamera = false),
        ClipEntity.of(photo, "A", 0),
    )
    private val copies = mapOf(
        uploaded.id to listOf(copy(uploaded, CopyPlace.Server), copy(uploaded, CopyPlace.Gallery)),
        onPhone.id to listOf(copy(onPhone, CopyPlace.Folder)),
        // A preview isn't a full-quality copy, so this clip isn't saved.
        previewOnly.id to listOf(copy(previewOnly, CopyPlace.Server, JobVariant.Preview)),
    )

    private val stats = MediaStats.of(clips, copies)

    @Test
    fun clipsAreGroupedByTheirSafestFullQualityCopy() {
        assertEquals(SafetyCounts(backedUp = 1, phoneOnly = 1, notSaved = 2), stats.safety)
        assertEquals(1_200L, stats.safetyBytes[Safety.NotSaved])
    }

    @Test
    fun countsAndSizes() {
        assertEquals(3, stats.videos)
        assertEquals(1, stats.photos)
        assertEquals(3_200L, stats.footageBytes)
        // Camera A still has three of them, each with a 100-byte preview (which isn't a file of its own).
        assertEquals(listOf<Pair<String?, Files>>("A" to Files(count = 3, original = 2_200, preview = 300)), stats.onCards)
        assertEquals(Files(count = 2, original = 1_000), stats.phone)
        assertEquals(listOf<Pair<String?, Files>>("s1" to Files(count = 2, original = 500, preview = 500)), stats.byServer)
    }

    @Test
    fun filesAddUpByVersion() {
        val f = Files()
            .plus(com.openpro.media.ClipVersion.Original, 10)
            .plus(com.openpro.media.ClipVersion.Stabilized(com.openpro.quik.StabilizationChoice.AntiShake), 20)
            .plus(com.openpro.media.ClipVersion.Preview, 5, file = false)
        assertEquals(Files(count = 2, original = 10, stabilized = 20, preview = 5), f)
        assertEquals(35L, f.bytes)
    }

    @Test
    fun monthsSkipUndatedClips() {
        assertEquals(
            listOf(
                YearMonth.of(2026, 8) to SafetyCounts(backedUp = 1, phoneOnly = 1),
                YearMonth.of(2026, 9) to SafetyCounts(notSaved = 1),
            ),
            stats.byMonth,
        )
    }

    @Test
    fun camerasMostClipsFirst() {
        assertEquals(listOf("A", "B"), stats.byCamera.map { it.first })
        assertEquals(3, stats.byCamera.first().second.total)
    }
}
