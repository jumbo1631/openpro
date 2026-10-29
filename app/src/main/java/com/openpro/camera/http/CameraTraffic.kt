package com.openpro.camera.http

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything OpenPro fetches from the camera passes through here:
 *  - a gate so at most [MAX_CONNECTIONS] transfers run at once (the camera's web server is small
 *    and can fall over under many parallel requests); a slot is held until the response is closed;
 *  - a meter that reports what is being transferred and how fast, for the in-app activity bar.
 * Traffic from GoPro's engine process (stabilization) is reported separately by its progress.
 */
object CameraTraffic {
    const val MAX_CONNECTIONS = 3

    enum class Kind(val label: String) {
        List("media list"),
        Thumbnail("thumbnails"),
        Preview("preview stream"),
        Original("original file"),
        Other("camera"),
    }

    data class Snapshot(
        val active: Map<Kind, Int> = emptyMap(),
        val bytesPerSecond: Long = 0,
        val sessionBytes: Long = 0,
    ) {
        val busy get() = active.values.any { it > 0 }
    }

    private val gate = Semaphore(MAX_CONNECTIONS, true)
    private val total = AtomicLong()
    private val active = mutableMapOf<Kind, Int>()
    private val window = ArrayDeque<Pair<Long, Long>>() // (timestamp ms, bytes)
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    fun kindOf(path: String): Kind = when {
        path.contains("gpMediaList") -> Kind.List
        path.contains("gpMediaMetadata") -> Kind.Thumbnail
        path.endsWith(".LRV", ignoreCase = true) -> Kind.Preview
        path.contains("/videos/") -> Kind.Original
        else -> Kind.Other
    }

    val interceptor = Interceptor { chain ->
        val kind = kindOf(chain.request().url.encodedPath)
        if (!gate.tryAcquire(60, TimeUnit.SECONDS)) throw IOException("camera busy: too many transfers in progress")
        changeActive(kind, +1)
        val response = try {
            chain.proceed(chain.request())
        } catch (e: Exception) {
            changeActive(kind, -1)
            gate.release()
            throw e
        }
        response.newBuilder().body(MeteredBody(response.body, kind)).build()
    }

    @Synchronized
    private fun changeActive(kind: Kind, delta: Int) {
        active[kind] = ((active[kind] ?: 0) + delta).coerceAtLeast(0)
        publish()
    }

    @Synchronized
    private fun counted(bytes: Long) {
        total.addAndGet(bytes)
        window.addLast(System.currentTimeMillis() to bytes)
        publish()
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        while (window.isNotEmpty() && now - window.first().first > 2_000) window.removeFirst()
        val rate = window.sumOf { it.second } / 2
        _snapshot.value = Snapshot(active.filterValues { it > 0 }, rate, total.get())
    }

    /** Recomputes the rate when nothing is flowing, so the bar settles to idle. */
    @Synchronized
    fun tick() = publish()

    private class MeteredBody(private val delegate: ResponseBody, private val kind: Kind) : ResponseBody() {
        private var released = false
        private val source: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val n = super.read(sink, byteCount)
                    if (n > 0) counted(n)
                    return n
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = source

        override fun close() {
            try {
                delegate.close()
            } finally {
                synchronized(this) {
                    if (!released) {
                        released = true
                        changeActive(kind, -1)
                        gate.release()
                    }
                }
            }
        }
    }
}
