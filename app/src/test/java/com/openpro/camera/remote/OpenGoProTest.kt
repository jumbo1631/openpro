package com.openpro.camera.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.TimeZone

class OpenGoProTest {
    @Test
    fun parsesTlvLists() {
        val payload = byteArrayOf(70, 1, 87, 35, 4, 0, 0, 0x0E, 0x10, 10, 1, 1)
        val tlv = OpenGoPro.parseTlv(payload)
        assertEquals(listOf(70 to 87L, 35 to 3600L, 10 to 1L), tlv.map { it.id to it.asLong })
    }

    @Test
    fun ignoresTruncatedTlv() {
        assertEquals(1, OpenGoPro.parseTlv(byteArrayOf(2, 1, 3, 54, 4, 0)).size)
    }

    @Test
    fun buildsCommands() {
        assertArrayEquals(byteArrayOf(1, 1, 1), OpenGoPro.shutter(true))
        assertArrayEquals(byteArrayOf(0x3E, 2, 0x03, 0xE9.toByte()), OpenGoPro.loadPresetGroup(1001))
        assertArrayEquals(byteArrayOf(0x40, 4, 0, 1, 0, 2), OpenGoPro.loadPreset(0x00010002))
        assertArrayEquals(byteArrayOf(0x13, 2, 70), OpenGoPro.query(0x13, listOf(2, 70)))
        val t = OpenGoPro.setDateTime(LocalDateTime.of(2026, 10, 26, 21, 5, 9))
        assertArrayEquals(byteArrayOf(0x0D, 7, 0x07, 0xEA.toByte(), 10, 26, 21, 5, 9), t)
        val local = OpenGoPro.setLocalDateTime(LocalDateTime.of(2026, 1, 2, 3, 4, 5), TimeZone.getTimeZone("GMT+10:00"), 0)
        assertEquals(12, local.size)
        assertEquals(600, ((local[9].toInt() and 0xFF) shl 8) or (local[10].toInt() and 0xFF))
    }

    // --- protobuf ---

    private fun varint(out: ByteArrayOutputStream, v: Long) {
        var x = v
        while (true) {
            if (x and 0x7F.inv().toLong() == 0L) { out.write(x.toInt()); return }
            out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
        }
    }
    private fun field(out: ByteArrayOutputStream, n: Int, v: Long) { varint(out, (n.toLong() shl 3)); varint(out, v) }
    private fun field(out: ByteArrayOutputStream, n: Int, b: ByteArray) { varint(out, (n.toLong() shl 3) or 2); varint(out, b.size.toLong()); out.write(b) }

    private fun preset(id: Long, mode: Int, title: Int, number: Int = 0, name: String? = null) = ByteArrayOutputStream().also {
        field(it, 1, id); field(it, 2, mode.toLong()); field(it, 3, title.toLong()); field(it, 4, number.toLong())
        field(it, 7, byteArrayOf(8, 2, 16, 1)) // a setting entry, skipped
        name?.let { n -> field(it, 10, n.toByteArray()) }
    }.toByteArray()

    @Test
    fun parsesPresetStatus() {
        val group = ByteArrayOutputStream().also {
            field(it, 1, 1000)
            field(it, 2, preset(65536, 12, 1))
            field(it, 2, preset(65537, 12, 2, 2))
            field(it, 2, preset(65538, 12, 18, name = "Ski"))
        }.toByteArray()
        val msg = ByteArrayOutputStream().also { field(it, 1, group) }.toByteArray()
        val groups = OpenGoPro.parsePresetStatus(msg)
        assertEquals(1000, groups.single().id)
        assertEquals(listOf("Standard", "Cinematic 2", "Ski"), groups.single().presets.map { it.label })
        assertEquals(65537L, groups.single().presets[1].id)
    }

    @Test
    fun namesKnownSettingValuesAndNumbersUnknownOnes() {
        assertEquals("60 fps", OpenGoPro.settingValueLabel(3, 5))
        assertEquals("Option 77", OpenGoPro.settingValueLabel(3, 77))
    }
}
