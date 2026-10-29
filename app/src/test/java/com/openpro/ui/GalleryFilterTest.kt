package com.openpro.ui

import com.openpro.batch.JobVariant
import com.openpro.copies.ClipEntity
import com.openpro.copies.CopyEntity
import com.openpro.copies.CopyPlace
import com.openpro.media.CameraMedia
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryFilterTest {
    private fun clip(name: String) = CameraMedia("100GOPRO", name, 1_000, 1_786_353_824, 0, 100, null, null)

    private val onCameraA = clip("GX010001.MP4")
    private val goneSaved = clip("GX010002.MP4")
    private val onCameraB = clip("GX010003.MP4")
    private val media = listOf(onCameraA, goneSaved, onCameraB)

    private val clips = mapOf(
        onCameraA.id to ClipEntity.of(onCameraA, "A", 0),
        goneSaved.id to ClipEntity.of(goneSaved, "A", 0).copy(onCamera = false),
        onCameraB.id to ClipEntity.of(onCameraB, "B", 0),
    )

    private fun copy(m: CameraMedia, place: CopyPlace) = CopyEntity(
        id = m.name + place, clipId = m.id, variant = JobVariant.Original, stabilization = null, place = place, location = "x",
        serverId = "s1".takeIf { place == CopyPlace.Server }, fileName = m.name, placeLabel = "", onRemovable = false,
        sizeBytes = 1_000, sha1 = null, cameraSerial = null, savedAt = 0,
    )

    private val copies = mapOf(
        goneSaved.id to listOf(copy(goneSaved, CopyPlace.Gallery), copy(goneSaved, CopyPlace.Server)),
        onCameraB.id to listOf(copy(onCameraB, CopyPlace.Server)),
    )

    private fun names(options: GalleryOptions) = options.filter(media, clips, copies).map { it.name }

    @Test
    fun filtersBySource() {
        assertEquals(media.map { it.name }, names(GalleryOptions(source = Source.All)))
        assertEquals(listOf("GX010001.MP4", "GX010003.MP4"), names(GalleryOptions(source = Source.Camera)))
        assertEquals(listOf("GX010002.MP4"), names(GalleryOptions(source = Source.Phone)))
        assertEquals(listOf("GX010002.MP4", "GX010003.MP4"), names(GalleryOptions(source = Source.Server)))
    }

    @Test
    fun filtersByCameraToo() {
        assertEquals(listOf("GX010001.MP4", "GX010002.MP4"), names(GalleryOptions(camera = "A")))
        assertEquals(listOf("GX010001.MP4"), names(GalleryOptions(camera = "A", source = Source.Camera)))
        assertEquals(listOf("GX010003.MP4"), names(GalleryOptions(camera = "B", source = Source.Server)))
    }
}
