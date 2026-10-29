package com.openpro.camera.ble

import java.io.ByteArrayOutputStream

/**
 * GoPro BLE message framing, as decoded by Quik's BlePacket.kt:
 *  - first byte bits 7..5 = 000: 5-bit length in the same byte
 *  - 001: 13-bit length across the first two bytes
 *  - 010: 16-bit length in the next two bytes
 *  - 1xxx: continuation packet (low 4 bits are a rolling counter)
 * Packets are at most 20 bytes. Responses start with the command ID then a status byte.
 */
object BlePackets {
    const val MAX_PACKET = 20

    fun packetize(message: ByteArray): List<ByteArray> {
        val header = when {
            message.size < 32 -> byteArrayOf(message.size.toByte())
            message.size < 8192 -> byteArrayOf((0x20 or (message.size shr 8)).toByte(), message.size.toByte())
            message.size < 65536 -> byteArrayOf(0x40, (message.size shr 8).toByte(), message.size.toByte())
            else -> throw IllegalArgumentException("message too long: ${message.size}")
        }
        val packets = mutableListOf<ByteArray>()
        var offset = 0
        val first = minOf(MAX_PACKET - header.size, message.size)
        packets += header + message.copyOfRange(0, first)
        offset += first
        var counter = 0
        while (offset < message.size) {
            val n = minOf(MAX_PACKET - 1, message.size - offset)
            packets += byteArrayOf((0x80 or (counter and 0x0F)).toByte()) + message.copyOfRange(offset, offset + n)
            offset += n
            counter++
        }
        return packets
    }

    /** Reassembles notifications from one response characteristic. */
    class Assembler {
        private val buffer = ByteArrayOutputStream()
        private var expected = -1

        /** Returns a complete message (without framing) or null if more packets are needed. */
        fun accept(packet: ByteArray): ByteArray? {
            if (packet.isEmpty()) return null
            val b0 = packet[0].toInt() and 0xFF
            val start: Int
            if (b0 and 0x80 != 0) {
                if (expected < 0) return null // stray continuation
                start = 1
            } else {
                buffer.reset()
                when (b0 and 0xE0) {
                    0x00 -> { expected = b0 and 0x1F; start = 1 }
                    0x20 -> { expected = ((b0 and 0x1F) shl 8) or (packet[1].toInt() and 0xFF); start = 2 }
                    0x40 -> { expected = ((packet[1].toInt() and 0xFF) shl 8) or (packet[2].toInt() and 0xFF); start = 3 }
                    else -> return null
                }
            }
            buffer.write(packet, start, packet.size - start)
            if (buffer.size() >= expected) {
                val out = buffer.toByteArray().copyOf(expected)
                buffer.reset()
                expected = -1
                return out
            }
            return null
        }
    }
}

/** A parsed command/setting response: [id][status][payload...]. */
class BleResponse(val id: Int, val status: Int, val payload: ByteArray) {
    val ok get() = status == 0

    companion object {
        fun parse(message: ByteArray): BleResponse? =
            if (message.size < 2) null
            else BleResponse(message[0].toInt() and 0xFF, message[1].toInt() and 0xFF, message.copyOfRange(2, message.size))
    }
}
