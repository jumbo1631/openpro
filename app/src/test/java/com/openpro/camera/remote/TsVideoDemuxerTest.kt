package com.openpro.camera.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TsVideoDemuxerTest {
    private val pmtPid = 0x100
    private val videoPid = 0x1011
    private val privatePid = 0x201

    // Frames shaped like the camera's: access unit delimiter, then an IDR (with SPS/PPS) or a P slice.
    private val key = byteArrayOf(0, 0, 0, 1, 9, 0x10, 0, 0, 0, 1, 0x67, 0x4d, 0, 0, 0, 1, 0x68, 0x01, 0, 0, 0, 1, 0x65) + ByteArray(500) { 0x55 }
    private val delta = byteArrayOf(0, 0, 0, 1, 9, 0x30, 0, 0, 0, 1, 0x41) + ByteArray(300) { 0x33 }

    @Test
    fun splitsVideoIntoFramesAndIgnoresOtherStreams() {
        val ts = ByteArrayOutputStream()
        ts.write(pat())
        ts.write(pmt())
        ts.write(pes(videoPid, key, pts = 30_000))
        ts.write(pes(privatePid, ByteArray(100) { -1 }, pts = 30_000, streamId = 0xbf))
        ts.write(pes(videoPid, delta, pts = 33_003))
        // The camera restarts its timestamps on a preset change; frames keep coming regardless.
        ts.write(pes(videoPid, key, pts = 30_000))
        ts.write(pes(videoPid, delta, pts = 33_003))

        val frames = mutableListOf<TsVideoDemuxer.Frame>()
        val demuxer = TsVideoDemuxer { frames += it }
        val bytes = ts.toByteArray()
        // Odd chunk sizes, so packets are split across feeds.
        var i = 0
        while (i < bytes.size) {
            val n = minOf(97, bytes.size - i)
            demuxer.feed(bytes, i, n)
            i += n
        }
        demuxer.feed(pes(videoPid, delta, pts = 36_006)) // the next frame's start completes the last one

        assertEquals(4, frames.size)
        assertEquals(listOf(true, false, true, false), frames.map { it.keyFrame })
        assertArrayEquals(key, frames[0].data)
        assertArrayEquals(delta, frames[1].data)
        assertFalse(frames[0].hevc)
    }

    @Test
    fun boundedPesIsDeliveredWithoutWaitingForTheNextFrame() {
        val frames = mutableListOf<TsVideoDemuxer.Frame>()
        val demuxer = TsVideoDemuxer { frames += it }
        demuxer.feed(pat() + pmt() + pes(videoPid, key, pts = 30_000, bounded = true))
        assertEquals(1, frames.size)
        assertArrayEquals(key, frames[0].data)
    }

    @Test
    fun recognisesKeyFrames() {
        assertTrue(TsVideoDemuxer.isKeyFrame(key, hevc = false))
        assertFalse(TsVideoDemuxer.isKeyFrame(delta, hevc = false))
        assertTrue(TsVideoDemuxer.isKeyFrame(byteArrayOf(0, 0, 1, 0x26, 0x01), hevc = true)) // IDR_W_RADL
        assertFalse(TsVideoDemuxer.isKeyFrame(byteArrayOf(0, 0, 1, 0x02, 0x01), hevc = true)) // TRAIL_R
    }

    private fun pat(): ByteArray = section(0, byteArrayOf(0x00, 0x01, (0xe0 or (pmtPid shr 8)).toByte(), pmtPid.toByte()), tableId = 0)

    private fun pmt(): ByteArray {
        val streams = byteArrayOf(
            TsVideoDemuxer.TYPE_H264.toByte(), (0xe0 or (videoPid shr 8)).toByte(), videoPid.toByte(), 0xf0.toByte(), 0,
            0x81.toByte(), (0xe0 or (privatePid shr 8)).toByte(), privatePid.toByte(), 0xf0.toByte(), 0,
        )
        // PCR PID, then an empty program info loop.
        val body = byteArrayOf((0xe0 or (videoPid shr 8)).toByte(), videoPid.toByte(), 0xf0.toByte(), 0) + streams
        return section(pmtPid, body, tableId = 2)
    }

    /** One PSI section in one packet: table header, [body], a dummy CRC. */
    private fun section(pid: Int, body: ByteArray, tableId: Int): ByteArray {
        val length = 5 + body.size + 4
        val s = byteArrayOf(0, tableId.toByte(), (0xb0 or (length shr 8)).toByte(), length.toByte(), 0, 1, 0xc1.toByte(), 0, 0) + body + byteArrayOf(0, 0, 0, 0)
        return packets(pid, s)
    }

    private fun pes(pid: Int, payload: ByteArray, pts: Long, streamId: Int = 0xe0, bounded: Boolean = false): ByteArray {
        val header = byteArrayOf(
            0, 0, 1, streamId.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
            (0x21 or ((pts shr 29).toInt() and 0x0e)).toByte(), (pts shr 22).toByte(), ((pts shr 14).toInt() or 1).toByte(),
            (pts shr 7).toByte(), ((pts shl 1).toInt() or 1).toByte(),
        )
        if (bounded) {
            val length = header.size - 6 + payload.size
            header[4] = (length shr 8).toByte()
            header[5] = length.toByte()
        }
        return packets(pid, header + payload)
    }

    /** Splits [data] into TS packets, padding the last one with adaptation-field stuffing. */
    private fun packets(pid: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        var first = true
        while (i < data.size) {
            val n = minOf(184, data.size - i)
            val p = ByteArray(188)
            p[0] = 0x47
            p[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            p[2] = pid.toByte()
            if (n == 184) {
                p[3] = 0x10
                System.arraycopy(data, i, p, 4, n)
            } else {
                val stuffing = 184 - n - 1
                p[3] = 0x30
                p[4] = stuffing.toByte()
                if (stuffing > 0) {
                    p[5] = 0
                    for (k in 6 until 5 + stuffing) p[k] = -1
                }
                System.arraycopy(data, i, p, 5 + stuffing, n)
            }
            out.write(p)
            i += n
            first = false
        }
        return out.toByteArray()
    }
}
