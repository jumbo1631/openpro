package com.openpro.ui

import com.openpro.batch.JobVariant
import com.openpro.copies.CopyEntity
import com.openpro.copies.CopyPlace
import com.openpro.media.CameraMedia
import com.openpro.media.ClipVersion
import com.openpro.quik.StabilizationChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteGroupsTest {
    private fun clip(name: String) = CameraMedia("100GOPRO", name, 1_000, 1_786_353_824, 0, 100, null, null)

    private val a = clip("GX010001.MP4")
    private val b = clip("GX010002.MP4")
    private val photo = clip("GOPR0003.JPG")

    private fun camera(m: CameraMedia) = m to ClipCopy(ClipVersion.Original, ClipCopy.Place.Camera, "On the camera", m.name, 1_000, ClipCopy.Ref.Camera(m))
    private fun indexed(m: CameraMedia, v: ClipVersion, place: CopyPlace) = ClipCopy.Ref.Indexed(
        CopyEntity(
            id = "${m.name}$v$place", clipId = m.id,
            variant = when (v) {
                ClipVersion.Original -> JobVariant.Original
                ClipVersion.Preview -> JobVariant.Preview
                is ClipVersion.Stabilized -> JobVariant.Stabilized
            },
            stabilization = (v as? ClipVersion.Stabilized)?.method, place = place, location = "x", serverId = "s1".takeIf { place == CopyPlace.Server },
            fileName = m.name, placeLabel = "", onRemovable = false, sizeBytes = 10, sha1 = null, cameraSerial = null, savedAt = 0,
        ),
    )
    private fun phone(m: CameraMedia, v: ClipVersion) = m to ClipCopy(v, ClipCopy.Place.Phone(false), "Phone gallery", m.name, 10, indexed(m, v, CopyPlace.Gallery))
    private fun server(m: CameraMedia, v: ClipVersion) = m to ClipCopy(v, ClipCopy.Place.Server("s1"), "Nextcloud", "/x/${m.name}", 10, indexed(m, v, CopyPlace.Server))

    @Test
    fun groupsByPlaceThenVersionThenMethod() {
        val items = listOf(
            camera(a), camera(b), camera(photo),
            phone(a, ClipVersion.Stabilized(StabilizationChoice.Suggested)),
            phone(b, ClipVersion.Stabilized(StabilizationChoice.Suggested)),
            phone(b, ClipVersion.Stabilized(StabilizationChoice.AntiShake)),
            phone(photo, ClipVersion.Original),
            server(a, ClipVersion.Original),
            // On a removed card: shown on the clip page, but nothing to delete.
            a to ClipCopy(ClipVersion.Original, ClipCopy.Place.Phone(true), "Kept on SD", null, null, null),
        )
        val groups = deleteGroups(items)
        assertEquals(listOf("camera", "phone", "server:s1"), groups.map { it.key })
        assertEquals(3, groups[0].items.size)

        val phoneGroup = groups[1]
        assertEquals(4, phoneGroup.items.size)
        assertEquals(listOf("Photos" to 1, "Stabilized" to 3), phoneGroup.children.map { it.label to it.items.size })
        val methods = phoneGroup.children[1].children
        assertEquals(listOf("GoPro default" to 2, "HyperSmooth" to 1), methods.map { it.label to it.items.size })

        assertEquals("On Nextcloud", groups[2].label)
        assertEquals(listOf("phone/original", "phone/stabilized/default", "phone/stabilized/hypersmooth"), phoneGroup.leaves.map { it.key })
    }
}
