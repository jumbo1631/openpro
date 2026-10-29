package com.openpro.camera.remote

import java.time.LocalDateTime
import java.util.TimeZone

/**
 * Message formats and IDs from GoPro's public Open GoPro specification (BLE + HTTP).
 * Pure Kotlin so it can be unit-tested.
 */
object OpenGoPro {
    // Commands (command characteristic): [id][param length][params]
    const val CMD_SHUTTER = 0x01
    const val CMD_SLEEP = 0x05
    const val CMD_SET_DATE_TIME = 0x0D
    const val CMD_SET_LOCAL_DATE_TIME = 0x0F
    const val CMD_HILIGHT = 0x18
    const val CMD_LOAD_PRESET_GROUP = 0x3E
    const val CMD_LOAD_PRESET = 0x40

    // Queries (query characteristic): [id][ids…]; no ids = all
    const val Q_SETTINGS = 0x12
    const val Q_STATUSES = 0x13
    const val Q_CAPABILITIES = 0x32
    const val Q_REGISTER_SETTINGS = 0x52
    const val Q_REGISTER_STATUSES = 0x53
    const val Q_REGISTER_CAPABILITIES = 0x62
    const val Q_UNREGISTER_SETTINGS = 0x72
    const val Q_UNREGISTER_STATUSES = 0x73
    const val Q_UNREGISTER_CAPABILITIES = 0x82
    // Pushed updates after registering
    const val PUSH_SETTINGS = 0x92
    const val PUSH_STATUSES = 0x93
    const val PUSH_CAPABILITIES = 0xA2

    // Protobuf: [feature][action][message]
    const val FEATURE_QUERY = 0xF5
    const val ACTION_GET_PRESETS = 0x72
    const val ACTION_PRESETS_RESPONSE = 0xF2
    const val ACTION_PRESETS_PUSH = 0xF3

    // Statuses
    const val ST_BATTERY_BARS = 2
    const val ST_HOT = 6
    const val ST_BUSY = 8
    const val ST_ENCODING = 10
    const val ST_VIDEO_PROGRESS = 13
    const val ST_SD_STATUS = 33
    const val ST_PHOTOS_REMAINING = 34
    const val ST_VIDEO_REMAINING = 35
    const val ST_SD_REMAINING_KB = 54
    const val ST_BATTERY_PERCENT = 70
    const val ST_READY = 82
    const val ST_COLD = 85
    const val ST_PRESET_GROUP = 96
    const val ST_PRESET = 97

    val WATCHED_STATUSES = listOf(
        ST_BATTERY_BARS, ST_HOT, ST_BUSY, ST_ENCODING, ST_VIDEO_PROGRESS, ST_SD_STATUS, ST_PHOTOS_REMAINING,
        ST_VIDEO_REMAINING, ST_SD_REMAINING_KB, ST_BATTERY_PERCENT, ST_READY, ST_COLD, ST_PRESET_GROUP, ST_PRESET,
    )

    // Preset groups
    const val GROUP_VIDEO = 1000
    const val GROUP_PHOTO = 1001
    const val GROUP_TIMELAPSE = 1002
    val GROUPS = listOf(GROUP_VIDEO to "Video", GROUP_PHOTO to "Photo", GROUP_TIMELAPSE to "Time lapse")

    /** One [id][length][value] entry from a query response. */
    data class Tlv(val id: Int, val value: ByteArray) {
        val asLong: Long get() = value.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    }

    fun parseTlv(payload: ByteArray): List<Tlv> {
        val out = mutableListOf<Tlv>()
        var i = 0
        while (i + 1 < payload.size) {
            val id = payload[i].toInt() and 0xFF
            val len = payload[i + 1].toInt() and 0xFF
            if (i + 2 + len > payload.size) break
            out += Tlv(id, payload.copyOfRange(i + 2, i + 2 + len))
            i += 2 + len
        }
        return out
    }

    fun query(id: Int, ids: List<Int> = emptyList()) = byteArrayOf(id.toByte()) + ids.map { it.toByte() }.toByteArray()

    fun shutter(on: Boolean) = byteArrayOf(CMD_SHUTTER.toByte(), 1, if (on) 1 else 0)

    fun loadPresetGroup(group: Int) = byteArrayOf(CMD_LOAD_PRESET_GROUP.toByte(), 2, (group shr 8).toByte(), group.toByte())

    fun loadPreset(id: Long) = byteArrayOf(
        CMD_LOAD_PRESET.toByte(), 4, (id shr 24).toByte(), (id shr 16).toByte(), (id shr 8).toByte(), id.toByte(),
    )

    /** Local date/time with UTC offset (minutes) and DST flag. */
    fun setLocalDateTime(now: LocalDateTime, tz: TimeZone, epochMs: Long): ByteArray {
        val offsetMin = tz.getOffset(epochMs) / 60_000
        val dst = tz.inDaylightTime(java.util.Date(epochMs))
        return byteArrayOf(
            CMD_SET_LOCAL_DATE_TIME.toByte(), 10,
            (now.year shr 8).toByte(), now.year.toByte(), now.monthValue.toByte(), now.dayOfMonth.toByte(),
            now.hour.toByte(), now.minute.toByte(), now.second.toByte(),
            (offsetMin shr 8).toByte(), offsetMin.toByte(), if (dst) 1 else 0,
        )
    }

    /** Date/time without time zone, for cameras that don't take the local variant. */
    fun setDateTime(now: LocalDateTime) = byteArrayOf(
        CMD_SET_DATE_TIME.toByte(), 7,
        (now.year shr 8).toByte(), now.year.toByte(), now.monthValue.toByte(), now.dayOfMonth.toByte(),
        now.hour.toByte(), now.minute.toByte(), now.second.toByte(),
    )

    // --- presets (protobuf NotifyPresetStatus) ---

    data class Preset(val id: Long, val mode: Int, val titleId: Int, val titleNumber: Int, val userDefined: Boolean, val customName: String?) {
        val label: String
            get() = customName?.takeIf { it.isNotBlank() }
                ?: (PRESET_TITLES[titleId] ?: FLAT_MODES[mode] ?: "Preset").let { if (titleNumber > 0) "$it $titleNumber" else it }
    }

    data class PresetGroup(val id: Int, val presets: List<Preset>)

    fun parsePresetStatus(bytes: ByteArray): List<PresetGroup> =
        Proto(bytes).fields().filter { it.number == 1 && it.bytes != null }.map { group ->
            val g = Proto(group.bytes!!).fields()
            PresetGroup(
                id = g.firstOrNull { it.number == 1 }?.varint?.toInt() ?: 0,
                presets = g.filter { it.number == 2 && it.bytes != null }.map { p ->
                    val f = Proto(p.bytes!!).fields()
                    fun v(n: Int) = f.firstOrNull { it.number == n }?.varint ?: 0
                    Preset(
                        id = v(1),
                        mode = v(2).toInt(),
                        titleId = v(3).toInt(),
                        titleNumber = v(4).toInt(),
                        userDefined = v(5) != 0L,
                        customName = f.firstOrNull { it.number == 10 }?.bytes?.toString(Charsets.UTF_8),
                    )
                },
            )
        }

    /** Minimal protobuf reader: varint and length-delimited fields (all the preset messages use). */
    class Proto(private val data: ByteArray) {
        data class Field(val number: Int, val varint: Long?, val bytes: ByteArray?)

        private var pos = 0

        private fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("truncated varint")
        }

        fun fields(): List<Field> {
            val out = mutableListOf<Field>()
            pos = 0
            while (pos < data.size) {
                val key = readVarint()
                val number = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> out += Field(number, readVarint(), null)
                    1 -> pos += 8
                    2 -> {
                        val len = readVarint().toInt()
                        if (len < 0 || pos + len > data.size) throw IllegalArgumentException("bad length")
                        out += Field(number, null, data.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    5 -> pos += 4
                    else -> throw IllegalArgumentException("unsupported wire type")
                }
            }
            return out
        }
    }

    // --- names (public spec tables; unknown values are shown by number) ---

    val PRESET_TITLES = mapOf(
        0 to "Activity", 1 to "Standard", 2 to "Cinematic", 3 to "Photo", 4 to "Live Burst", 5 to "Burst",
        6 to "Night", 7 to "Time Warp", 8 to "Time Lapse", 9 to "Night Lapse", 10 to "Video", 11 to "Slo-Mo",
        13 to "Photo", 14 to "Panorama", 16 to "Time Warp", 18 to "Custom",
    )

    val FLAT_MODES = mapOf(
        12 to "Video", 13 to "Time Lapse Video", 15 to "Looping", 16 to "Single Photo", 17 to "Photo", 18 to "Night Photo",
        19 to "Burst", 20 to "Time Lapse Photo", 21 to "Night Lapse Photo", 24 to "Time Warp", 25 to "Live Burst",
        26 to "Night Lapse Video", 27 to "Slo-Mo",
    )

    /** Settings OpenPro knows by name, with names for their values. Only ones the camera offers are shown. */
    val SETTINGS: Map<Int, Pair<String, Map<Long, String>>> = linkedMapOf(
        2 to ("Resolution" to mapOf(
            1L to "4K", 4L to "2.7K", 6L to "2.7K 4:3", 7L to "1440", 9L to "1080", 18L to "4K 4:3", 24L to "5K",
            25L to "5K 4:3", 26L to "5.3K 8:7", 27L to "5.3K 4:3", 28L to "4K 8:7", 100L to "5.3K", 107L to "5.3K",
            108L to "4K", 109L to "4K", 110L to "1080", 111L to "2.7K",
        )),
        3 to ("Frame rate" to mapOf(
            0L to "240 fps", 1L to "120 fps", 2L to "100 fps", 5L to "60 fps", 6L to "50 fps", 8L to "30 fps",
            9L to "25 fps", 10L to "24 fps", 13L to "200 fps",
        )),
        121 to ("Lens" to mapOf(
            0L to "Wide", 2L to "Narrow", 3L to "SuperView", 4L to "Linear", 7L to "Max SuperView",
            8L to "Linear + Horizon Leveling", 9L to "HyperView", 10L to "Linear + Horizon Lock", 11L to "Max HyperView",
            12L to "Ultra SuperView", 13L to "Ultra Wide", 14L to "Ultra Linear", 104L to "Ultra HyperView",
        )),
        135 to ("HyperSmooth" to mapOf(0L to "Off", 1L to "Low", 2L to "High", 3L to "Boost", 4L to "Auto Boost", 100L to "Standard")),
        134 to ("Anti-flicker" to mapOf(2L to "60 Hz", 3L to "50 Hz")),
        59 to ("Auto power down" to mapOf(
            0L to "Never", 1L to "1 min", 4L to "5 min", 6L to "15 min", 7L to "30 min", 11L to "8 s", 12L to "30 s",
        )),
        162 to ("Max lens mod" to mapOf(0L to "Off", 1L to "On")),
        178 to ("Wireless band" to mapOf(0L to "2.4 GHz", 1L to "5 GHz")),
    )

    fun settingValueLabel(setting: Int, value: Long): String = SETTINGS[setting]?.second?.get(value) ?: "Option $value"
}
