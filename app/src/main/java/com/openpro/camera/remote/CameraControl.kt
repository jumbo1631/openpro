package com.openpro.camera.remote

import android.util.Log
import com.openpro.camera.ble.BleResponse
import com.openpro.camera.ble.GoProBle
import com.openpro.camera.ble.GoProUuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.TimeZone

/** What the camera last told us, over Bluetooth. Values are the raw numbers from the spec. */
data class RemoteState(
    val statuses: Map<Int, Long> = emptyMap(),
    val settings: Map<Int, Long> = emptyMap(),
    /** Byte length of each setting's value, to write it back the same way. */
    val settingLengths: Map<Int, Int> = emptyMap(),
    /** Values the camera currently allows, per setting. */
    val capabilities: Map<Int, List<Long>> = emptyMap(),
    /** Null until read; empty if the camera doesn't support preset queries. */
    val presets: List<OpenGoPro.PresetGroup>? = null,
    /** True while the camera pushes updates (registration worked); otherwise OpenPro polls. */
    val pushing: Boolean = false,
    val updatedAt: Long = 0,
    /** Features the camera refused or didn't answer, by name. */
    val unsupported: Set<String> = emptySet(),
) {
    val recording get() = statuses[OpenGoPro.ST_ENCODING] == 1L
    val busy get() = statuses[OpenGoPro.ST_BUSY] == 1L
    val presetGroup get() = statuses[OpenGoPro.ST_PRESET_GROUP]?.toInt()
    val activePreset get() = statuses[OpenGoPro.ST_PRESET]
}

class CameraCommandException(message: String) : Exception(message)

/**
 * Remote control of the camera over Bluetooth, from the public Open GoPro spec. [start] while a
 * remote screen is visible: reads everything once, registers for pushed updates, and polls the
 * important statuses as a fallback. Every command reports the camera's refusal as an exception.
 */
class CameraControl(private val ble: GoProBle, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val listener = launch { listen() }
            try {
                refreshAll()
                register()
                while (isActive) {
                    delay(if (_state.value.pushing) 5_000 else 2_000)
                    // Pushes cover most changes; still poll the key statuses in case some are missed.
                    runCatching { readStatuses(OpenGoPro.WATCHED_STATUSES) }
                }
            } finally {
                listener.cancel()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope.launch {
            runCatching { ble.query(OpenGoPro.query(OpenGoPro.Q_UNREGISTER_STATUSES, OpenGoPro.WATCHED_STATUSES), 3_000) }
            runCatching { ble.query(OpenGoPro.query(OpenGoPro.Q_UNREGISTER_SETTINGS), 3_000) }
            runCatching { ble.query(OpenGoPro.query(OpenGoPro.Q_UNREGISTER_CAPABILITIES), 3_000) }
        }
        _state.update { it.copy(pushing = false) }
    }

    suspend fun refreshAll() {
        runCatching { readStatuses(emptyList()) }.onFailure { unsupported("status") }
        runCatching { readSettings() }.onFailure { unsupported("settings") }
        runCatching { readCapabilities() }.onFailure { unsupported("capabilities") }
        runCatching { readPresets() }.onFailure {
            Log.i(TAG, "presets not available: $it")
            _state.update { s -> s.copy(presets = emptyList()) }
        }
    }

    /** The camera's free SD card space in bytes, asked once over Bluetooth; null if it won't say (no card, not connected). */
    suspend fun sdFreeBytes(): Long? = runCatching {
        readStatuses(listOf(OpenGoPro.ST_SD_REMAINING_KB))
        _state.value.statuses[OpenGoPro.ST_SD_REMAINING_KB]?.takeIf { it > 0 }?.times(1024)
    }.getOrNull()

    private suspend fun register() {
        val ok = runCatching { ble.query(OpenGoPro.query(OpenGoPro.Q_REGISTER_STATUSES, OpenGoPro.WATCHED_STATUSES)).ok }.getOrDefault(false)
        runCatching { ble.query(OpenGoPro.query(OpenGoPro.Q_REGISTER_SETTINGS, OpenGoPro.SETTINGS.keys.toList())) }
        runCatching { ble.query(OpenGoPro.query(OpenGoPro.Q_REGISTER_CAPABILITIES, OpenGoPro.SETTINGS.keys.toList())) }
        _state.update { it.copy(pushing = ok) }
    }

    /** Applies pushed updates. */
    private suspend fun listen() {
        ble.messages.collect { (uuid, r) ->
            if (uuid != GoProUuids.QUERY_RESPONSE) return@collect
            when (r.id) {
                OpenGoPro.PUSH_STATUSES, OpenGoPro.Q_REGISTER_STATUSES -> applyStatuses(r)
                OpenGoPro.PUSH_SETTINGS, OpenGoPro.Q_REGISTER_SETTINGS -> applySettings(r)
                OpenGoPro.PUSH_CAPABILITIES, OpenGoPro.Q_REGISTER_CAPABILITIES -> applyCapabilities(r)
                OpenGoPro.FEATURE_QUERY -> if (r.status == OpenGoPro.ACTION_PRESETS_PUSH) {
                    runCatching { OpenGoPro.parsePresetStatus(r.payload) }.getOrNull()?.let { groups -> _state.update { it.copy(presets = groups) } }
                }
            }
        }
    }

    private suspend fun readStatuses(ids: List<Int>) = applyStatuses(checked(ble.query(OpenGoPro.query(OpenGoPro.Q_STATUSES, ids)), "status"))

    private suspend fun readSettings() = applySettings(checked(ble.query(OpenGoPro.query(OpenGoPro.Q_SETTINGS)), "settings"))

    private suspend fun readCapabilities() = applyCapabilities(checked(ble.query(OpenGoPro.query(OpenGoPro.Q_CAPABILITIES)), "capabilities"))

    private suspend fun readPresets() {
        val r = ble.query(byteArrayOf(OpenGoPro.FEATURE_QUERY.toByte(), OpenGoPro.ACTION_GET_PRESETS.toByte()), 5_000)
        if (r.status != OpenGoPro.ACTION_PRESETS_RESPONSE && r.status != OpenGoPro.ACTION_PRESETS_PUSH) {
            throw CameraCommandException("unexpected preset response ${r.status}")
        }
        _state.update { it.copy(presets = OpenGoPro.parsePresetStatus(r.payload)) }
    }

    private fun applyStatuses(r: BleResponse) {
        if (!r.ok) return
        val values = OpenGoPro.parseTlv(r.payload).filter { it.value.size <= 8 }.associate { it.id to it.asLong }
        _state.update { it.copy(statuses = it.statuses + values, updatedAt = System.currentTimeMillis()) }
    }

    private fun applySettings(r: BleResponse) {
        if (!r.ok) return
        val tlv = OpenGoPro.parseTlv(r.payload).filter { it.value.size in 1..8 }
        _state.update { s ->
            s.copy(
                settings = s.settings + tlv.associate { it.id to it.asLong },
                settingLengths = s.settingLengths + tlv.associate { it.id to it.value.size },
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun applyCapabilities(r: BleResponse) {
        if (!r.ok) return
        // One entry per allowed value, setting by setting.
        val caps = OpenGoPro.parseTlv(r.payload).filter { it.value.size in 1..8 }.groupBy({ it.id }, { it.asLong })
        if (caps.isNotEmpty()) _state.update { it.copy(capabilities = it.capabilities + caps) }
    }

    private fun checked(r: BleResponse, what: String): BleResponse {
        if (!r.ok) throw CameraCommandException("The camera refused the $what query (status ${r.status})")
        return r
    }

    private fun unsupported(feature: String) = _state.update { it.copy(unsupported = it.unsupported + feature) }

    // --- commands ---

    private suspend fun command(message: ByteArray, what: String) {
        val r = ble.commandRaw(message)
        if (!r.ok) throw CameraCommandException(refusal(what, r.status))
    }

    private fun refusal(what: String, status: Int) = when (status) {
        1 -> "The camera couldn't $what right now"
        2 -> "The camera doesn't support $what"
        else -> "The camera refused to $what (status $status)"
    }

    suspend fun shutter(on: Boolean) {
        command(OpenGoPro.shutter(on), if (on) "start capturing" else "stop capturing")
        runCatching { readStatuses(OpenGoPro.WATCHED_STATUSES) }
    }

    suspend fun hilight() = command(byteArrayOf(OpenGoPro.CMD_HILIGHT.toByte()), "add a HiLight")

    suspend fun loadPresetGroup(group: Int) {
        command(OpenGoPro.loadPresetGroup(group), "switch mode")
        runCatching { readStatuses(OpenGoPro.WATCHED_STATUSES) }
        runCatching { readSettings() }
        runCatching { readCapabilities() }
    }

    suspend fun loadPreset(id: Long) {
        command(OpenGoPro.loadPreset(id), "load that preset")
        runCatching { readStatuses(OpenGoPro.WATCHED_STATUSES) }
        runCatching { readSettings() }
        runCatching { readCapabilities() }
    }

    suspend fun setSetting(id: Int, value: Long) {
        val len = _state.value.settingLengths[id] ?: 1
        val bytes = ByteArray(len) { i -> (value shr (8 * (len - 1 - i))).toByte() }
        val r = ble.settingRaw(id, bytes)
        if (!r.ok) throw CameraCommandException(refusal("change ${OpenGoPro.SETTINGS[id]?.first ?: "setting $id"}", r.status))
        // Other settings' allowed values can change with this one (e.g. frame rates per resolution).
        _state.update { it.copy(settings = it.settings + (id to value)) }
        runCatching { readSettings() }
        runCatching { readCapabilities() }
    }

    /** Sets the camera's clock from the phone (with time zone where supported). */
    suspend fun syncTime() {
        val now = LocalDateTime.now()
        val r = ble.commandRaw(OpenGoPro.setLocalDateTime(now, TimeZone.getDefault(), System.currentTimeMillis()))
        if (r.ok) return
        command(OpenGoPro.setDateTime(now), "set its clock")
    }

    suspend fun sleep() = command(byteArrayOf(OpenGoPro.CMD_SLEEP.toByte()), "go to sleep")

    companion object {
        private const val TAG = "CameraControl"
    }
}
