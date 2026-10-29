package com.openpro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.openpro.camera.remote.PreviewDecoder
import com.openpro.camera.wifi.UdpForwarder
import com.openpro.link.LinkProtocol
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Debug builds only: replays a saved live-preview stream into the preview decoder over loopback
 * UDP, paced like the camera, without the camera. To record one, join a PC to the camera's Wi-Fi,
 * start the stream there (GET http://10.5.5.9:8080/gopro/camera/stream/start; the camera sends it
 * to whoever asked) and save it: `ffmpeg -i udp://@:8554 -map 0 -c copy -t 30 preview.ts`.
 *
 *   adb shell am broadcast -n com.openpro/.DebugPreviewReceiver \
 *       --es path /sdcard/Android/data/com.openpro/files/preview.ts --ei seconds 30
 *
 * Logs under "DebugPreview": every stretch of over 300 ms without a new frame, and a summary.
 */
class DebugPreviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val file = File(intent.getStringExtra("path") ?: File(context.getExternalFilesDir(null), "preview.ts").path)
        val seconds = intent.getIntExtra("seconds", 30)
        val data = file.readBytes()
        val port = UdpForwarder.freeLoopbackPort()
        val main = Handler(Looper.getMainLooper())
        val frames = ImageReader.newInstance(640, 480, ImageFormat.PRIVATE, 4)
        frames.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, main)
        val decoder = PreviewDecoder(port, frames.surface, object : PreviewDecoder.Listener {
            override fun onFirstFrame() {
                Log.i(TAG, "first frame")
            }

            override fun onVideoSize(width: Int, height: Int) {
                Log.i(TAG, "video ${width}x$height")
            }

            override fun onError(message: String) {
                Log.e(TAG, message)
            }
        }).start()

        var sending = true
        var lastCount = 0L
        var lastNewFrame = SystemClock.elapsedRealtime()
        var longest = 0L
        val watch = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val count = decoder.renderedFrames
                if (count != lastCount) {
                    val gap = now - lastNewFrame
                    if (lastCount > 0 && gap > 300) Log.w(TAG, "stall: no new frame for $gap ms")
                    if (lastCount > 0) longest = maxOf(longest, gap)
                    lastCount = count
                    lastNewFrame = now
                }
                if (sending) main.postDelayed(this, 20)
            }
        }
        main.post(watch)

        Thread({
            DatagramSocket().use { socket ->
                val chunk = 188 * 7
                val delayNs = seconds * 1_000_000_000L / (data.size / chunk).coerceAtLeast(1)
                val started = System.nanoTime()
                for ((n, offset) in (data.indices step chunk).withIndex()) {
                    val wait = started + n * delayNs - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    socket.send(DatagramPacket(data, offset, minOf(chunk, data.size - offset), LinkProtocol.LOOPBACK, port))
                }
            }
            main.postDelayed({
                sending = false
                Log.i(TAG, "done: ${decoder.renderedFrames} frames drawn, longest gap between frames $longest ms")
                decoder.stop()
                frames.close()
            }, 1_000)
        }, "debug-preview").start()
        Log.i(TAG, "replaying ${file.name} (${data.size} bytes) to port $port over $seconds s")
    }

    companion object {
        private const val TAG = "DebugPreview"
    }
}
