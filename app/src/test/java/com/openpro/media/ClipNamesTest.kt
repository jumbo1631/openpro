package com.openpro.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import com.openpro.quik.StabilizationChoice
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class ClipNamesTest {
    // The camera reports local wall-clock time stored as if UTC.
    private fun clip(name: String, at: LocalDateTime?, size: Long = 42_205_184) =
        CameraMedia("100GOPRO", name, size, at?.toEpochSecond(ZoneOffset.UTC) ?: 0, 0, -1, null, null)

    private val shot = LocalDateTime.of(2026, 8, 24, 14, 23, 44)

    @Test
    fun savedNamesLeadWithTheRecordingTime() {
        val c = clip("GX010849.MP4", shot)
        assertEquals("2026-08-24_14-23-44_GX010849.MP4", ClipNames.saved(c, ClipVersion.Original))
    }

    @Test
    fun stabilizedNamesSayHow() {
        val c = clip("GX010849.MP4", shot)
        assertEquals(
            "2026-08-24_14-23-44_GX010849_stabilized-hypersmooth.mp4",
            ClipNames.saved(c, ClipVersion.Stabilized(StabilizationChoice.AntiShake)),
        )
        assertEquals("2026-08-24_14-23-44_GX010849_stabilized-level.mp4", ClipNames.saved(c, ClipVersion.Stabilized(StabilizationChoice.HorizonLeveling)))
        val methods = StabilizationChoice.entries.map { ClipNames.saved(c, ClipVersion.Stabilized(it)) }
        assertEquals(methods.size, methods.toSet().size)
    }

    @Test
    fun previewsAreMp4s() {
        val c = clip("GX010849.MP4", shot)
        assertEquals("2026-08-24_14-23-44_GX010849_preview.mp4", ClipNames.saved(c, ClipVersion.Preview))
    }

    @Test
    fun photosAreImages() {
        val photo = clip("GOPR0042.JPG", shot)
        assertTrue(photo.isPhoto)
        assertEquals("image/jpeg", ClipNames.mimeType("2026-08-24_14-23-44_GOPR0042.JPG"))
        assertEquals("video/mp4", ClipNames.mimeType("GX010849_preview.mp4"))
    }

    @Test
    fun namesSortByWhenTheyWereShot() {
        val names = listOf(
            clip("GX010001.MP4", LocalDateTime.of(2026, 10, 1, 9, 0, 0)),
            clip("GX010849.MP4", shot),
            clip("GX010850.MP4", LocalDateTime.of(2026, 8, 24, 9, 5, 0)),
        ).map { ClipNames.saved(it, ClipVersion.Original) }
        assertEquals(listOf("GX010850", "GX010849", "GX010001"), names.sorted().map { it.substringAfterLast('_').substringBefore('.') })
    }

    @Test
    fun undatedClipsKeepTheCameraName() {
        assertEquals("GX010849.MP4", ClipNames.saved(clip("GX010849.MP4", null), ClipVersion.Original))
    }

    @Test
    fun copiesSavedUnderTheCameraNameAreStillFound() {
        val c = clip("GX010849.MP4", shot)
        assertEquals(listOf("2026-08-24_14-23-44_GX010849.MP4", "GX010849.MP4"), ClipNames.candidates(c, ClipVersion.Original))
    }

    @Test
    fun clipsWithTheSameNameAreToldApart() {
        // The camera reuses names after a card is formatted.
        val before = clip("GX010849.MP4", shot)
        val after = clip("GX010849.MP4", LocalDateTime.of(2026, 11, 2, 10, 0, 0), size = 9_000_000)
        assertNotEquals(before.id, after.id)
        assertNotEquals(ClipNames.workName(before), ClipNames.workName(after))
        assertEquals(before.path, after.path)
    }

    @Test
    fun cameraNamesMakeSafeFolderNames() {
        assertEquals("HERO12 Black", ClipNames.folderName("HERO12 Black"))
        assertEquals("My_GoPro _2_", ClipNames.folderName("My/GoPro <2>"))
        assertEquals(null, ClipNames.folderName("  ..  "))
        assertEquals(null, ClipNames.folderName(null))
    }
}
