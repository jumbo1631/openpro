package com.openpro.camera.remote

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.openpro.link.LinkProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Shows the camera's live preview (MPEG-TS over UDP on loopback [listenPort]) on [surface], drawing each
 * frame the moment the hardware decoder finishes it. There is no player clock or buffer: the
 * camera restarts its timestamps on every preset change, and a timestamp-driven player would hold
 * the picture until the new timestamps caught up (as long as the previous preset had been on).
 *
 * Runs on its own thread; [listener] is called on the main thread.
 */
class PreviewDecoder(private val listenPort: Int, private val surface: Surface, private val listener: Listener) {
    interface Listener {
        fun onFirstFrame() {}
        fun onVideoSize(width: Int, height: Int) {}
        fun onError(message: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null
    private var codec: MediaCodec? = null
    private var codecHevc = false
    private var waitingForKey = true
    private var frames = 0L
    private var rendered = 0L
    private val info = MediaCodec.BufferInfo()

    /** Frames drawn so far (debug replay statistics). */
    val renderedFrames: Long get() = rendered

    fun start() = apply {
        running = true
        thread = Thread(::run, "preview-decoder").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
    }

    private fun run() {
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 500
                bind(InetSocketAddress(LinkProtocol.LOOPBACK, listenPort))
            }
        } catch (e: Exception) {
            Log.w(TAG, "can't listen on $listenPort", e)
            post { listener.onError("The preview couldn't start: ${e.message}") }
            return
        }
        val demuxer = TsVideoDemuxer(::decode)
        val buffer = ByteArray(65_535)
        val packet = DatagramPacket(buffer, buffer.size)
        var lastData = SystemClock.elapsedRealtime()
        var reportedSilence = false
        try {
            while (running) {
                try {
                    packet.setLength(buffer.size)
                    socket.receive(packet)
                    lastData = SystemClock.elapsedRealtime()
                    demuxer.feed(buffer, 0, packet.length)
                } catch (_: SocketTimeoutException) {
                    if (!reportedSilence && SystemClock.elapsedRealtime() - lastData > SILENCE_MS) {
                        reportedSilence = true
                        post { listener.onError("No video arrived from the camera within ${SILENCE_MS / 1000} seconds.") }
                    }
                }
                drain()
            }
        } finally {
            socket.close()
            releaseCodec()
        }
    }

    private fun decode(frame: TsVideoDemuxer.Frame) {
        if (codec != null && frame.hevc != codecHevc) releaseCodec()
        if (waitingForKey) {
            if (!frame.keyFrame) return // a decoder needs a key frame (with its parameter sets) to begin
            waitingForKey = false
        }
        try {
            val c = codec ?: createCodec(frame.hevc)
            var index = c.dequeueInputBuffer(INPUT_WAIT_US)
            if (index < 0) {
                drain()
                index = c.dequeueInputBuffer(INPUT_WAIT_US)
            }
            if (index < 0) {
                // The decoder fell behind; skipping a frame would corrupt the ones after it, so
                // start clean at the next key frame.
                Log.w(TAG, "decoder busy; waiting for the next key frame")
                waitingForKey = true
                return
            }
            val input = c.getInputBuffer(index)!!
            input.clear()
            if (frame.data.size > input.capacity()) {
                c.queueInputBuffer(index, 0, 0, 0, 0)
                waitingForKey = true
                return
            }
            input.put(frame.data)
            // Timestamps only need to increase; the camera's own reset on every preset change.
            c.queueInputBuffer(index, 0, frame.data.size, frames++ * FRAME_US, if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            drain()
        } catch (e: Exception) {
            // MediaCodec.CodecException, IllegalStateException: start over at the next key frame.
            Log.w(TAG, "decoder error; restarting at the next key frame", e)
            releaseCodec()
        }
    }

    private fun drain() {
        val c = codec ?: return
        try {
            while (true) {
                val index = c.dequeueOutputBuffer(info, 0)
                when {
                    index >= 0 -> {
                        c.releaseOutputBuffer(index, true)
                        if (rendered++ == 0L) post { listener.onFirstFrame() }
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                        Log.i(TAG, "video ${w}x$h")
                        post { listener.onVideoSize(w, h) }
                    }
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "decoder error while drawing; restarting at the next key frame", e)
            releaseCodec()
        }
    }

    private fun createCodec(hevc: Boolean): MediaCodec {
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        // The size is a hint; the real one comes from the stream's parameter sets. The maximum lets
        // the decoder follow size changes between presets without being recreated.
        val format = MediaFormat.createVideoFormat(mime, 640, 480).apply {
            setInteger(MediaFormat.KEY_MAX_WIDTH, 1920)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, 1080)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        val c = MediaCodec.createDecoderByType(mime)
        try {
            c.configure(format, surface, null, 0)
        } catch (e: Exception) {
            // Some decoders refuse the low-latency key; configure without it.
            Log.w(TAG, "low-latency configure refused; retrying without it", e)
            c.reset()
            format.removeKey(MediaFormat.KEY_LOW_LATENCY)
            c.configure(format, surface, null, 0)
        }
        c.start()
        Log.i(TAG, "decoder ${c.name} started")
        codec = c
        codecHevc = hevc
        return c
    }

    private fun releaseCodec() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        waitingForKey = true
    }

    private fun post(block: () -> Unit) {
        main.post { if (running) block() }
    }

    companion object {
        private const val TAG = "PreviewDecoder"
        private const val INPUT_WAIT_US = 50_000L
        private const val FRAME_US = 33_366L
        private const val SILENCE_MS = 15_000L
    }
}
