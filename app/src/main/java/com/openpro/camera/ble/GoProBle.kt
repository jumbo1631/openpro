package com.openpro.camera.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** UUIDs from Quik's BleConstants (identical to the public Open GoPro spec). */
object GoProUuids {
    val CONTROL_SERVICE: UUID = UUID.fromString("0000fea6-0000-1000-8000-00805f9b34fb")
    val COMMAND: UUID = gp("0072")
    val COMMAND_RESPONSE: UUID = gp("0073")
    val SETTING: UUID = gp("0074")
    val SETTING_RESPONSE: UUID = gp("0075")
    val QUERY: UUID = gp("0076")
    val QUERY_RESPONSE: UUID = gp("0077")
    val AP_SERVICE: UUID = gp("0001")
    val AP_SSID: UUID = gp("0002")
    val AP_PASSWORD: UUID = gp("0003")
    val AP_SWITCH: UUID = gp("0004")
    val AP_STATE: UUID = gp("0005")
    val NETWORK_SERVICE: UUID = gp("0090")
    val NETWORK_COMMAND: UUID = gp("0091")
    val NETWORK_RESPONSE: UUID = gp("0092")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun gp(short: String) = UUID.fromString("b5f9$short-aa8d-11e3-9046-0002a5d5c51b")
}

/** Command and setting IDs (Open GoPro; the same bytes Quik sends). */
object GoProCommands {
    const val SLEEP = 0x05
    const val SET_AP_CONTROL = 0x17
    const val GET_HARDWARE_INFO = 0x3C
    const val SETTING_KEEP_ALIVE = 0x5B
    const val KEEP_ALIVE_VALUE = 0x42
}

data class HardwareInfo(
    val modelId: Int,
    val modelName: String,
    val firmware: String,
    val serial: String,
    val apSsid: String,
    val apMac: String,
)

/** A camera seen while scanning. [modelId] is from its advertisement, when it says (Open GoPro cameras do). */
data class FoundCamera(val device: BluetoothDevice, val name: String, val rssi: Int, val modelId: Int? = null)

class BleException(message: String) : Exception(message)

/**
 * Minimal GoPro BLE client: scan, connect + bond, request/response on the control service, and
 * reading the WiFi AP credentials. BLE is control-only; GoPro cameras do not serve media over it.
 */
@SuppressLint("MissingPermission") // callers check BLUETOOTH_SCAN/CONNECT before use
class GoProBle(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter

    private var gatt: BluetoothGatt? = null
    private val gattLock = Mutex()
    private var pendingOp: CompletableDeferred<Any?>? = null
    private val assemblers = mutableMapOf<UUID, BlePackets.Assembler>()
    private val responses = MutableSharedFlow<Pair<UUID, BleResponse>>(extraBufferCapacity = 64)
    /** Every message from the camera, including pushed status/setting updates (0x92, 0x93, 0xA2). */
    val messages: kotlinx.coroutines.flow.SharedFlow<Pair<UUID, BleResponse>> = responses
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _enabled = MutableStateFlow(adapter?.isEnabled == true)
    /** Whether the phone's Bluetooth is on, following the system setting. */
    val enabled: StateFlow<Boolean> = _enabled

    init {
        context.registerReceiver(
            object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, intent: android.content.Intent) {
                    _enabled.value = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF) == BluetoothAdapter.STATE_ON
                }
            },
            android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    fun scan(): Flow<FoundCamera> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner ?: throw BleException("Bluetooth is off")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: "GoPro"
                val modelId = advertisedModelId(result.scanRecord?.getManufacturerSpecificData(GOPRO_COMPANY_ID))
                trySend(FoundCamera(result.device, name, result.rssi, modelId))
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException("scan failed ($errorCode)"))
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GoProUuids.CONTROL_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }

    fun deviceFor(address: String): BluetoothDevice = adapter.getRemoteDevice(address)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connected.value = false
                pendingOp?.completeExceptionally(BleException("disconnected (status $status)"))
                g.close()
                if (gatt === g) gatt = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) =
            completeStatus(status, "service discovery")

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = complete(mtu)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            completeStatus(status, "write ${c.uuid}")

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            completeStatus(status, "descriptor ${d.characteristic.uuid}")

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) complete(value)
            else pendingOp?.completeExceptionally(BleException("read ${c.uuid} failed ($status)"))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val assembler = synchronized(assemblers) { assemblers.getOrPut(c.uuid) { BlePackets.Assembler() } }
            val message = assembler.accept(value) ?: return
            BleResponse.parse(message)?.let { responses.tryEmit(c.uuid to it) }
        }

        private fun complete(value: Any?) {
            pendingOp?.complete(value)
        }

        private fun completeStatus(status: Int, what: String) {
            if (status == BluetoothGatt.GATT_SUCCESS) complete(Unit)
            else pendingOp?.completeExceptionally(BleException("$what failed (GATT status $status)"))
        }
    }

    /** Runs one GATT operation at a time and waits for its callback. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> op(timeoutMs: Long = 10_000, start: (BluetoothGatt) -> Boolean): T = gattLock.withLock {
        val g = gatt ?: throw BleException("not connected")
        val deferred = CompletableDeferred<Any?>()
        pendingOp = deferred
        try {
            if (!start(g)) throw BleException("GATT operation was rejected")
            withTimeout(timeoutMs) { deferred.await() } as T
        } finally {
            pendingOp = null
        }
    }

    suspend fun connect(device: BluetoothDevice) {
        disconnect()
        val deferred = CompletableDeferred<Any?>()
        gattLock.withLock {
            pendingOp = deferred
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            try {
                withTimeout(20_000) { deferred.await() }
            } finally {
                pendingOp = null
            }
        }
        op<Unit>(15_000) { it.discoverServices() }
        ensureBonded(device)
        for (uuid in listOf(GoProUuids.COMMAND_RESPONSE, GoProUuids.SETTING_RESPONSE, GoProUuids.QUERY_RESPONSE)) {
            enableNotifications(GoProUuids.CONTROL_SERVICE, uuid)
        }
        _connected.value = true
    }

    fun disconnect() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        _connected.value = false
        synchronized(assemblers) { assemblers.clear() }
    }

    private suspend fun ensureBonded(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        val bonded = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (d?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                    BluetoothDevice.BOND_BONDED -> bonded.complete(true)
                    BluetoothDevice.BOND_NONE -> bonded.complete(false)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        try {
            if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) {
                throw BleException("could not start pairing")
            }
            if (!withTimeout(60_000) { bonded.await() }) {
                throw BleException("pairing was rejected; put the camera in pairing mode and retry")
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private suspend fun enableNotifications(service: UUID, uuid: UUID) {
        val c = characteristic(service, uuid)
        gatt?.setCharacteristicNotification(c, true)
        val descriptor = c.getDescriptor(GoProUuids.CCCD) ?: return
        op<Unit> { it.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS }
    }

    private fun characteristic(service: UUID, uuid: UUID): BluetoothGattCharacteristic =
        gatt?.getService(service)?.getCharacteristic(uuid)
            ?: throw BleException("characteristic $uuid not found")

    private suspend fun write(service: UUID, uuid: UUID, message: ByteArray) {
        val c = characteristic(service, uuid)
        for (packet in BlePackets.packetize(message)) {
            op<Unit> { it.writeCharacteristic(c, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS }
        }
    }

    private suspend fun request(
        service: UUID, requestUuid: UUID, responseUuid: UUID, message: ByteArray, timeoutMs: Long = 8_000,
    ): BleResponse {
        val id = message[0].toInt() and 0xFF
        return coroutineScope {
            // Subscribe before writing so a fast response is not missed.
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                responses.first { (uuid, r) -> uuid == responseUuid && r.id == id }.second
            }
            try {
                write(service, requestUuid, message)
                withTimeout(timeoutMs) { response.await() }
            } finally {
                response.cancel()
            }
        }
    }

    suspend fun command(vararg bytes: Int): BleResponse = request(
        GoProUuids.CONTROL_SERVICE, GoProUuids.COMMAND, GoProUuids.COMMAND_RESPONSE,
        ByteArray(bytes.size) { bytes[it].toByte() },
    )

    suspend fun setting(id: Int, value: Int): BleResponse = request(
        GoProUuids.CONTROL_SERVICE, GoProUuids.SETTING, GoProUuids.SETTING_RESPONSE,
        byteArrayOf(id.toByte(), 1, value.toByte()),
    )

    /** A command with raw bytes: [id][param length][params]… */
    suspend fun commandRaw(message: ByteArray): BleResponse =
        request(GoProUuids.CONTROL_SERVICE, GoProUuids.COMMAND, GoProUuids.COMMAND_RESPONSE, message)

    /** Sets a setting to a value of any length (as the camera reported it). */
    suspend fun settingRaw(id: Int, value: ByteArray): BleResponse = request(
        GoProUuids.CONTROL_SERVICE, GoProUuids.SETTING, GoProUuids.SETTING_RESPONSE,
        byteArrayOf(id.toByte(), value.size.toByte()) + value,
    )

    /** A query (statuses, settings, capabilities, or a protobuf request) on the query characteristic. */
    suspend fun query(message: ByteArray, timeoutMs: Long = 8_000): BleResponse =
        request(GoProUuids.CONTROL_SERVICE, GoProUuids.QUERY, GoProUuids.QUERY_RESPONSE, message, timeoutMs)

    suspend fun readString(service: UUID, uuid: UUID): String {
        val c = characteristic(service, uuid)
        val value: ByteArray = op { it.readCharacteristic(c) }
        return value.toString(Charsets.UTF_8).trimEnd('\u0000')
    }

    suspend fun setWifiAp(enabled: Boolean) {
        val r = command(GoProCommands.SET_AP_CONTROL, 0x01, if (enabled) 0x01 else 0x00)
        if (!r.ok) throw BleException("camera refused WiFi AP ${if (enabled) "on" else "off"} (status ${r.status})")
    }

    /**
     * Waits until the camera reports its access point as running (AP state characteristic
     * b5f90005, non-zero = on), so Android's join dialog finds the network on its first scan.
     * If the state can't be read, falls back to a fixed settle delay.
     */
    suspend fun waitForWifiAp(timeoutMs: Long = 12_000) {
        val c = runCatching { characteristic(GoProUuids.AP_SERVICE, GoProUuids.AP_STATE) }.getOrNull()
        if (c == null) {
            delay(3_000)
            return
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = runCatching { op<ByteArray> { it.readCharacteristic(c) } }.getOrNull()
            if (state == null) {
                delay(3_000)
                return
            }
            if (state.any { it.toInt() != 0 }) {
                // The AP beacons slightly before its DHCP/web server is ready.
                delay(1_000)
                return
            }
            delay(500)
        }
        Log.w(TAG, "camera did not report its WiFi AP as up within ${timeoutMs}ms; trying anyway")
    }

    /** Whether the camera reports its access point as on (null if it can't be read). */
    suspend fun wifiApOn(): Boolean? = runCatching {
        val c = characteristic(GoProUuids.AP_SERVICE, GoProUuids.AP_STATE)
        op<ByteArray> { it.readCharacteristic(c) }.any { it.toInt() != 0 }
    }.getOrNull()

    suspend fun keepAlive() {
        setting(GoProCommands.SETTING_KEEP_ALIVE, GoProCommands.KEEP_ALIVE_VALUE)
    }

    suspend fun sleep() {
        runCatching { command(GoProCommands.SLEEP) }
    }

    suspend fun wifiSsid() = readString(GoProUuids.AP_SERVICE, GoProUuids.AP_SSID)

    suspend fun wifiPassword() = readString(GoProUuids.AP_SERVICE, GoProUuids.AP_PASSWORD)

    suspend fun hardwareInfo(): HardwareInfo {
        val r = command(GoProCommands.GET_HARDWARE_INFO)
        if (!r.ok) throw BleException("hardware info failed (status ${r.status})")
        return parseHardwareInfo(r.payload)
    }

    companion object {
        private const val TAG = "GoProBle"

        /** GoPro's Bluetooth company identifier, which its advertisements' manufacturer data is filed under. */
        const val GOPRO_COMPANY_ID = 0x02F2

        /**
         * The model ID from a camera's advertisement: the manufacturer data (after the company ID)
         * is [schema version][camera status][model ID]…, in both documented schema versions (2 and 3).
         * Null for other schemas, or a camera that doesn't send it.
         */
        fun advertisedModelId(manufacturerData: ByteArray?): Int? {
            val data = manufacturerData ?: return null
            if (data.size < 3 || (data[0].toInt() != 2 && data[0].toInt() != 3)) return null
            return data[2].toInt() and 0xFF
        }

        /** Payload is a run of [len][bytes] fields: model id, model name, board type, firmware, serial, AP SSID, AP MAC. */
        fun parseHardwareInfo(payload: ByteArray): HardwareInfo {
            val fields = mutableListOf<ByteArray>()
            var i = 0
            while (i < payload.size) {
                val len = payload[i].toInt() and 0xFF
                if (i + 1 + len > payload.size) break
                fields += payload.copyOfRange(i + 1, i + 1 + len)
                i += 1 + len
            }
            fun str(n: Int) = fields.getOrNull(n)?.toString(Charsets.UTF_8)?.trimEnd('\u0000') ?: ""
            val modelId = fields.getOrNull(0)?.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) } ?: -1
            val info = HardwareInfo(modelId, str(1), str(3), str(4), str(5), str(6))
            Log.i(TAG, "hardware info: $info")
            return info
        }
    }
}
