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

    private fun only(vararg filters: ClipFilter) = GalleryOptions(filters = filters.toList())

    @Test
    fun filtersByPlace() {
        assertEquals(media.map { it.name }, names(GalleryOptions()))
        assertEquals(listOf("GX010001.MP4", "GX010003.MP4"), names(only(ClipFilter(Facet.Camera))))
        assertEquals(listOf("GX010002.MP4"), names(only(ClipFilter(Facet.Phone))))
        assertEquals(listOf("GX010002.MP4", "GX010003.MP4"), names(only(ClipFilter(Facet.Server))))
    }

    @Test
    fun negatedFiltersExclude() {
        assertEquals(listOf("GX010002.MP4"), names(only(ClipFilter(Facet.Camera, negated = true))))
        assertEquals(listOf("GX010001.MP4", "GX010003.MP4"), names(only(ClipFilter(Facet.Phone, negated = true))))
        assertEquals(listOf("GX010001.MP4"), names(only(ClipFilter(Facet.Server, negated = true))))
    }

    @Test
    fun filtersStack() {
        // On a server but not on this phone: only the clip uploaded straight from the camera.
        assertEquals(listOf("GX010003.MP4"), names(only(ClipFilter(Facet.Server), ClipFilter(Facet.Phone, negated = true))))
        // On the camera and not on a server.
        assertEquals(listOf("GX010001.MP4"), names(only(ClipFilter(Facet.Camera), ClipFilter(Facet.Server, negated = true))))
        // Nothing is on the camera and off it at once.
        assertEquals(emptyList<String>(), names(only(ClipFilter(Facet.Camera), ClipFilter(Facet.Phone), ClipFilter(Facet.Server, negated = true))))
    }

    @Test
    fun filtersByStabilized() {
        val stabilized = copy(onCameraA, CopyPlace.Gallery).copy(id = "stab", variant = JobVariant.Stabilized)
        val withStabilized = copies + (onCameraA.id to listOf(stabilized))
        val yes = only(ClipFilter(Facet.Stabilized)).filter(media, clips, withStabilized).map { it.name }
        val no = only(ClipFilter(Facet.Stabilized, negated = true)).filter(media, clips, withStabilized).map { it.name }
        assertEquals(listOf("GX010001.MP4"), yes)
        assertEquals(listOf("GX010002.MP4", "GX010003.MP4"), no)
    }

    @Test
    fun filtersByFormat() {
        val photo = clip("GOPR0004.JPG")
        val withPhoto = media + photo
        fun names(vararg filters: ClipFilter) = GalleryOptions(filters = filters.toList()).filter(withPhoto, clips, copies).map { it.name }
        assertEquals(listOf("GX010001.MP4", "GX010002.MP4", "GX010003.MP4"), names(ClipFilter(Facet.Video)))
        assertEquals(listOf("GOPR0004.JPG"), names(ClipFilter(Facet.Photo)))
        assertEquals(listOf("GX010003.MP4"), names(ClipFilter(Facet.Video), ClipFilter(Facet.Server), ClipFilter(Facet.Phone, negated = true)))
    }

    @Test
    fun settingAFacetReplacesItsOldSetting() {
        val start = listOf(ClipFilter(Facet.Camera), ClipFilter(Facet.Video))
        // Video and Photo are one choice: picking Photo drops Video.
        assertEquals(listOf(ClipFilter(Facet.Camera), ClipFilter(Facet.Photo)), withFacet(start, Facet.Photo, true))
        // "No" negates, "Any" removes.
        assertEquals(listOf(ClipFilter(Facet.Video), ClipFilter(Facet.Camera, negated = true)), withFacet(start, Facet.Camera, false))
        assertEquals(listOf(ClipFilter(Facet.Video)), withFacet(start, Facet.Camera, null))
    }

    @Test
    fun filtersBySize() {
        val small = clip("GX010010.MP4").copy(sizeBytes = 50)
        val big = clip("GX010011.MP4").copy(sizeBytes = 5_000)
        val sized = listOf(small, onCameraA, big) // 50, 1 000, 5 000 bytes
        fun names(min: Long?, max: Long?) = GalleryOptions(minSize = min, maxSize = max).filter(sized, clips, copies).map { it.name }
        assertEquals(listOf("GX010010.MP4", "GX010001.MP4", "GX010011.MP4"), names(null, null))
        assertEquals(listOf("GX010001.MP4", "GX010011.MP4"), names(1_000, null))
        assertEquals(listOf("GX010010.MP4", "GX010001.MP4"), names(null, 1_000))
        assertEquals(listOf("GX010001.MP4"), names(100, 4_000))
    }

    @Test
    fun filtersByCameraToo() {
        assertEquals(listOf("GX010001.MP4", "GX010002.MP4"), names(GalleryOptions(camera = "A")))
        assertEquals(listOf("GX010001.MP4"), names(GalleryOptions(camera = "A", filters = listOf(ClipFilter(Facet.Camera)))))
        assertEquals(listOf("GX010003.MP4"), names(GalleryOptions(camera = "B", filters = listOf(ClipFilter(Facet.Server)))))
    }
}
