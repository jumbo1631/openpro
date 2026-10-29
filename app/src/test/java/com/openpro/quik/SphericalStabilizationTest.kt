package com.openpro.quik

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SphericalStabilizationTest {
    @Test
    fun matchesTheEngineSchema() {
        // director/parsers/schemas/0.30.json: spherical_stabilization with Custom settings.
        val s = Edl.spherical(worldLock = true)
        assertEquals("SphericalStabilization", s["type"]!!.jsonPrimitive.content)
        assertEquals(0.5, s["anti_shake_intensity"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val settings = s["settings"]!!.jsonObject
        assertEquals("Custom", settings["type"]!!.jsonPrimitive.content)
        assertEquals("true", settings["horizon_leveling"]!!.jsonPrimitive.content)
        assertEquals("true", settings["world_lock"]!!.jsonPrimitive.content)
        assertEquals("false", Edl.spherical(worldLock = false)["settings"]!!.jsonObject["world_lock"]!!.jsonPrimitive.content)
    }

    @Test
    fun goesIntoTheEditList() {
        val edl = Json.parseToJsonElement(Edl.singleClip(Edl.PROVIDER_FILE, "/x/GS010001.360", Edl.uuid4For("x"), Edl.spherical(false))).jsonObject
        val clip = edl["content"]!!.jsonArray[0].jsonObject
        assertEquals("SphericalStabilization", clip["stabilization"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun kindsFitTheirClips() {
        assertTrue(StabilizationChoice.Suggested.fits(true))
        assertTrue(StabilizationChoice.Suggested.fits(false))
        assertTrue(StabilizationChoice.SphericalWorldLock.fits(true))
        assertFalse(StabilizationChoice.SphericalWorldLock.fits(false))
        assertTrue(StabilizationChoice.AntiShake.fits(false))
        assertFalse(StabilizationChoice.AntiShake.fits(true))
    }
}
