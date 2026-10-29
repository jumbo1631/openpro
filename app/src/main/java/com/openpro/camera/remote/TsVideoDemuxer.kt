package com.openpro.camera.remote

import java.io.ByteArrayOutputStream

/**
 * Pulls the video out of the camera's live-preview MPEG-TS, one frame (access unit) at a time.
 *
 * Only what the preview needs: the PAT names the PMT, the PMT names the H.264 or H.265 stream, and
 * each PES packet on that stream is one frame in Annex B form (the camera sends one frame per PES,
 * with no B-frames, so arrival order is display order). Other streams (audio, and a private stream
 * the camera labels AC-3) are ignored. Timestamps are ignored too: the camera restarts them from
 * zero whenever its encoder restarts (on every preset change), and a viewfinder shows each frame
 * as soon as it arrives anyway.
 *
 * Bytes may be fed in any chunking; TS packets split across chunks are reassembled.
 */
class TsVideoDemuxer(private val onFrame: (Frame) -> Unit) {
    class Frame(val data: ByteArray, val keyFrame: Boolean, val hevc: Boolean)

    private val carry = ByteArray(PACKET)
    private var carried = 0
    private var pmtPid = -1
    private var videoPid = -1
    private var hevc = false
    private val pes = ByteArrayOutputStream(64 * 1024)
    private var pesLength = 0
    private var inPes = false

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        var i = offset
        val end = offset + length
        if (carried > 0) {
            val take = minOf(PACKET - carried, end - i)
            System.arraycopy(bytes, i, carry, carried, take)
            carried += take
            i += take
            if (carried < PACKET) return
            packet(carry, 0)
            carried = 0
        }
        while (i < end) {
            if (bytes[i] != SYNC) {
                i++ // lost sync: skip to the next sync byte
                continue
            }
            if (end - i < PACKET) {
                System.arraycopy(bytes, i, carry, 0, end - i)
                carried = end - i
                return
            }
            packet(bytes, i)
            i += PACKET
        }
    }

    private fun packet(p: ByteArray, o: Int) {
        val unitStart = p[o + 1].toInt() and 0x40 != 0
        val pid = ((p[o + 1].toInt() and 0x1f) shl 8) or (p[o + 2].toInt() and 0xff)
        val adaptation = (p[o + 3].toInt() shr 4) and 3
        if (adaptation and 1 == 0) return // no payload
        var start = o + 4
        if (adaptation and 2 != 0) start += 1 + (p[o + 4].toInt() and 0xff)
        val stop = o + PACKET
        if (start >= stop) return
        when {
            pid == 0 && unitStart -> parsePat(p, start, stop)
            pid == pmtPid && unitStart -> parsePmt(p, start, stop)
            pid == videoPid -> video(p, start, stop, unitStart)
        }
    }

    private fun parsePat(p: ByteArray, from: Int, stop: Int) {
        val s = from + 1 + (p[from].toInt() and 0xff) // skip pointer field
        if (s + 8 > stop || p[s].toInt() != 0) return
        val sectionEnd = minOf(stop, s + 3 + (((p[s + 1].toInt() and 0x0f) shl 8) or (p[s + 2].toInt() and 0xff)) - 4)
        var i = s + 8
        while (i + 4 <= sectionEnd) {
            val program = ((p[i].toInt() and 0xff) shl 8) or (p[i + 1].toInt() and 0xff)
            if (program != 0) {
                pmtPid = ((p[i + 2].toInt() and 0x1f) shl 8) or (p[i + 3].toInt() and 0xff)
                return
            }
            i += 4
        }
    }

    private fun parsePmt(p: ByteArray, from: Int, stop: Int) {
        val s = from + 1 + (p[from].toInt() and 0xff)
        if (s + 12 > stop || p[s].toInt() != 2) return
        val sectionEnd = minOf(stop, s + 3 + (((p[s + 1].toInt() and 0x0f) shl 8) or (p[s + 2].toInt() and 0xff)) - 4)
        var i = s + 12 + (((p[s + 10].toInt() and 0x0f) shl 8) or (p[s + 11].toInt() and 0xff))
        while (i + 5 <= sectionEnd) {
            val type = p[i].toInt() and 0xff
            val pid = ((p[i + 1].toInt() and 0x1f) shl 8) or (p[i + 2].toInt() and 0xff)
            if (type == TYPE_H264 || type == TYPE_H265) {
                if (pid != videoPid) resetPes()
                videoPid = pid
                hevc = type == TYPE_H265
                return
            }
            i += 5 + (((p[i + 3].toInt() and 0x0f) shl 8) or (p[i + 4].toInt() and 0xff))
        }
    }

    private fun video(p: ByteArray, from: Int, stop: Int, unitStart: Boolean) {
        var start = from
        if (unitStart) {
            finishPes()
            if (stop - start < 9 || p[start].toInt() != 0 || p[start + 1].toInt() != 0 || p[start + 2].toInt() != 1) return
            pesLength = ((p[start + 4].toInt() and 0xff) shl 8) or (p[start + 5].toInt() and 0xff)
            val headerLength = 9 + (p[start + 8].toInt() and 0xff)
            // A bounded PES length counts the bytes after its length field, header included.
            if (pesLength != 0) pesLength -= headerLength - 6
            start += headerLength
            if (start > stop) return
            inPes = true
        }
        if (!inPes) return
        pes.write(p, start, stop - start)
        // With a known length the frame is complete now, without waiting for the next one.
        if (pesLength > 0 && pes.size() >= pesLength) finishPes()
    }

    private fun finishPes() {
        if (inPes && pes.size() > 0) {
            val data = pes.toByteArray()
            onFrame(Frame(data, isKeyFrame(data, hevc), hevc))
        }
        resetPes()
    }

    private fun resetPes() {
        pes.reset()
        pesLength = 0
        inPes = false
    }

    companion object {
        const val PACKET = 188
        private const val SYNC = 0x47.toByte()
        const val TYPE_H264 = 0x1b
        const val TYPE_H265 = 0x24

        /** True if the Annex B data holds an IDR picture (H.264 type 5; H.265 IRAP types 16 to 21). */
        fun isKeyFrame(data: ByteArray, hevc: Boolean): Boolean {
            var i = 0
            while (i + 3 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                    val header = data[i + 3].toInt() and 0xff
                    if (hevc) {
                        if ((header shr 1) and 0x3f in 16..21) return true
                    } else if (header and 0x1f == 5) {
                        return true
                    }
                    i += 3
                } else {
                    i++
                }
            }
            return false
        }
    }
}
