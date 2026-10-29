package com.openpro.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipDurationsTest {
    private fun info(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun readsTheLengthFromClipMetadata() {
        assertEquals(61_061L, ClipDurations.lengthMs(info("""{"dur":"61","msdur":"61061"}""")))
        assertEquals(61_000L, ClipDurations.lengthMs(info("""{"dur":61}""")))
        assertNull(ClipDurations.lengthMs(info("""{"w":"1920"}""")))
    }

    @Test
    fun formatsLikeAVideoPlayer() {
        assertEquals("0:42", ClipDurations.format(42_000))
        assertEquals("12:05", ClipDurations.format(725_000))
        assertEquals("1:02:03", ClipDurations.format(3_723_000))
    }
}
