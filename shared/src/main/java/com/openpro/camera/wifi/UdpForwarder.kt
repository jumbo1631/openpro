package com.openpro.camera.wifi

import android.net.Network
import com.openpro.link.LinkProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Receives the camera's live preview (MPEG-TS over UDP, sent to the phone on [listenPort]) on the
 * camera's network and passes each datagram to 127.0.0.1:[targetPort], where the player listens.
 * Used by OpenPro directly, and by Camera Link when a VPN covers OpenPro.
 */
class UdpForwarder(network: Network?, listenPort: Int, private val targetPort: Int) {
    private val input = DatagramSocket(null).apply {
        reuseAddress = true
        network?.bindSocket(this)
        bind(InetSocketAddress(listenPort))
    }
    private val output = DatagramSocket()
    @Volatile var packets = 0L
        private set

    private val thread = Thread({
        val buffer = ByteArray(65_535)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (!input.isClosed) {
                packet.setLength(buffer.size)
                input.receive(packet)
                output.send(DatagramPacket(buffer, packet.length, LinkProtocol.LOOPBACK, targetPort))
                packets++
            }
        } catch (_: SocketException) {
            // closed
        }
    }, "preview-forwarder").apply { isDaemon = true }

    fun start() = apply { thread.start() }

    fun close() {
        input.close()
        output.close()
    }

    companion object {
        /** Where GoPro cameras send the preview stream. */
        const val CAMERA_PREVIEW_PORT = 8554

        /** A currently free loopback UDP port for the player. */
        fun freeLoopbackPort(): Int = DatagramSocket(0, LinkProtocol.LOOPBACK).use { it.localPort }
    }
}
