package com.openpro.link

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.random.Random

/** The relay against a fake camera web server on loopback. */
class RelayTest {
    private lateinit var camera: ServerSocket
    private val seen = Collections.synchronizedList(mutableListOf<String>())
    private val body = Random(42).nextBytes(3 * 1024 * 1024 + 17)
    private lateinit var relay: Relay

    @Before
    fun setUp() {
        camera = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        Thread {
            while (!camera.isClosed) {
                val s = runCatching { camera.accept() }.getOrNull() ?: break
                Thread {
                    s.use {
                        val head = readHead(s.getInputStream())
                        seen += head
                        val range = Regex("(?im)^Range: bytes=(\\d+)-").find(head)?.groupValues?.get(1)?.toInt()
                        val out = s.getOutputStream()
                        if (range != null) {
                            val part = body.copyOfRange(range, body.size)
                            out.write("HTTP/1.1 206 Partial Content\r\nContent-Length: ${part.size}\r\n\r\n".toByteArray())
                            out.write(part)
                        } else {
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                            out.write(body)
                        }
                        out.flush()
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
        relay = Relay(SocketFactory.getDefault(), "127.0.0.1", camera.localPort).also { it.start() }
    }

    @After
    fun tearDown() {
        relay.close()
        camera.close()
    }

    private fun get(path: String, range: Int? = null): Pair<Int, ByteArray> {
        val c = URL("http://127.0.0.1:${relay.port}$path").openConnection() as HttpURLConnection
        range?.let { c.setRequestProperty("Range", "bytes=$it-") }
        val code = c.responseCode
        val stream = if (code < 400) c.inputStream else c.errorStream
        return code to stream.use { it.readBytes() }
    }

    @Test
    fun relaysWithTokenStrippedAndBodyIntact() {
        val (code, bytes) = get("/${relay.token}/videos/DCIM/100GOPRO/GX010001.MP4?a=1")
        assertEquals(200, code)
        assertArrayEquals(body, bytes)
        val head = seen.single()
        assertTrue(head, head.startsWith("GET /videos/DCIM/100GOPRO/GX010001.MP4?a=1 HTTP/1.1"))
        assertTrue(head, head.contains("Host: 127.0.0.1:${camera.localPort}"))
        assertTrue(head, head.contains("Connection: close"))
        assertTrue(head, !head.contains(relay.token))
    }

    @Test
    fun rejectsMissingOrWrongToken() {
        assertEquals(403, get("/videos/DCIM/100GOPRO/GX010001.MP4").first)
        assertEquals(403, get("/00000000000000000000000000000000/gp/gpMediaList").first)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun forwardsRangeRequests() {
        val (code, bytes) = get("/${relay.token}/videos/DCIM/100GOPRO/GX010001.MP4", range = 1_000_000)
        assertEquals(206, code)
        assertArrayEquals(body.copyOfRange(1_000_000, body.size), bytes)
        assertTrue(seen.single().contains("Range: bytes=1000000-"))
    }

    @Test
    fun handlesParallelRequests() {
        val pool = Executors.newFixedThreadPool(3)
        val results = (0 until 6).map { pool.submit<Pair<Int, ByteArray>> { get("/${relay.token}/videos/x.MP4") } }
        results.forEach { r ->
            val (code, bytes) = r.get(30, TimeUnit.SECONDS)
            assertEquals(200, code)
            assertArrayEquals(body, bytes)
        }
        pool.shutdown()
    }

    @Test
    fun reportsUnreachableCameraAs502() {
        camera.close()
        Thread.sleep(50)
        assertEquals(502, get("/${relay.token}/gp/gpMediaList").first)
    }

    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            if (out.size() >= 4 && out.toString().endsWith("\r\n\r\n")) break
        }
        return out.toString()
    }
}
