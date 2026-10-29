package com.openpro.link

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.net.SocketFactory

/**
 * Relays the camera's HTTP server to 127.0.0.1:[port]. Each request must have the path
 * /<[token]>/<camera path>; the prefix is stripped and the request is sent to the camera through
 * [sockets] (bound to the camera's network) with "Connection: close", then both directions are
 * piped until either side closes.
 * One camera connection per request keeps the relay simple and stateless; the camera is fine
 * with that (it is how most of its clients talk to it).
 */
class Relay(
    private val sockets: SocketFactory,
    private val host: String = LinkProtocol.CAMERA_HOST,
    private val cameraPort: Int = LinkProtocol.CAMERA_PORT,
) {
    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 16, LinkProtocol.LOOPBACK)
    val port: Int = server.localPort
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "relay").apply { isDaemon = true } }
    @Volatile private var closed = false

    fun start() {
        pool.execute {
            while (!closed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    if (!closed) log("accept failed: $e")
                    break
                }
                pool.execute { handle(client) }
            }
        }
        log("relay listening on 127.0.0.1:$port")
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        client.use {
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size != 3) return respond(output, 400, "Bad request")
            val (method, target, version) = parts
            val prefix = "/$token/"
            if (!target.startsWith(prefix)) return respond(output, 403, "Forbidden")
            val path = "/" + target.removePrefix(prefix)
            val headers = lines.drop(1).filter { line ->
                val name = line.substringBefore(':').trim().lowercase()
                line.isNotEmpty() && name !in DROPPED_HEADERS
            }
            val request = buildString {
                append("$method $path $version\r\n")
                append("Host: $host:$cameraPort\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            val camera = try {
                sockets.createSocket().apply {
                    connect(InetSocketAddress(host, cameraPort), 10_000)
                    soTimeout = 60_000
                }
            } catch (e: IOException) {
                log("camera connect failed: $e")
                return respond(output, 502, "Camera unreachable: ${e.message}")
            }
            camera.use {
                camera.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
                // Request bodies (rare for the camera) follow the head; pipe them along.
                pool.execute { pipe(input, camera.getOutputStream()) { runCatching { camera.shutdownOutput() } } }
                pipe(camera.getInputStream(), output) {}
            }
        }
    }

    private fun pipe(from: InputStream, to: OutputStream, onEnd: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
            }
            to.flush()
        } catch (_: IOException) {
            // Either side went away; the sockets are closed by the caller.
        } finally {
            onEnd()
        }
    }

    /** Reads up to the blank line ending the request head (max 32 KB). */
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (out.size() < 32 * 1024) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (matched == 4) return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun respond(output: OutputStream, code: Int, message: String) {
        val body = message.toByteArray()
        runCatching {
            output.write("HTTP/1.1 $code $message\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(body)
            output.flush()
        }
    }

    /** android.util.Log isn't available in JVM unit tests. */
    private fun log(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    companion object {
        private const val TAG = "Relay"
        private val DROPPED_HEADERS = setOf("host", "connection", "keep-alive", "proxy-connection", "upgrade")
    }
}
