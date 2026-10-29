package com.openpro.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class PathLayoutTest {
    private val shot = LocalDateTime.of(2026, 8, 24, 14, 23, 44).toEpochSecond(ZoneOffset.UTC)
    private fun clip(created: Long = shot) = CameraMedia("100GOPRO", "GX010849.MP4", 1, created, 0, 0, null, null)

    @Test
    fun fillsInCameraAndDate() {
        assertEquals("HERO12 Black", PathLayout.resolve("{camera}", clip(), "HERO12 Black", "HERO12 Black"))
        assertEquals("My GoPro/2026-08", PathLayout.resolve("{camera}/{year}-{month}", clip(), "My GoPro", "HERO12 Black"))
        assertEquals("2026/08/24", PathLayout.resolve("{year}/{month}/{day}", clip(), null, null))
        assertEquals("HERO12 Black", PathLayout.resolve("{model}", clip(), "My GoPro", "HERO12 Black"))
    }

    @Test
    fun noFoldersForAnEmptyLayout() {
        assertNull(PathLayout.resolve("", clip(), "HERO12 Black", null))
        assertNull(PathLayout.resolve("//", clip(), "HERO12 Black", null))
    }

    @Test
    fun undatedClipsGoInUndated() {
        assertEquals("HERO12 Black/undated", PathLayout.resolve("{camera}/{year}-{month}", clip(created = 0), "HERO12 Black", null))
    }

    @Test
    fun foldersAreMadeSafe() {
        assertEquals("a_b", PathLayout.resolve("{camera}", clip(), "a:b", null))
        assertEquals("Unknown camera", PathLayout.resolve("{camera}", clip(), null, null))
    }

    @Test
    fun knowsWhenCamerasGetTheirOwnFolders() {
        assertTrue(PathLayout.usesCamera("{camera}/{year}"))
        assertFalse(PathLayout.usesCamera("{year}-{month}"))
    }
}
