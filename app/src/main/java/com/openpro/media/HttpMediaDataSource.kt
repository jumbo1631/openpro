package com.openpro.media

import android.media.MediaDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Random-access reads of a file on the camera through HTTP Range requests, so
 * MediaMetadataRetriever can pull one frame without downloading the file. Reads go through
 * [client], i.e. over the camera network. Small reads are served from cached 256 KB blocks.
 */
class HttpMediaDataSource(
    private val client: OkHttpClient,
    private val url: String,
    private val length: Long,
) : MediaDataSource() {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > MAX_BLOCKS
    }

    override fun getSize(): Long = length

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        var copied = 0
        var pos = position
        while (copied < size && pos < length) {
            val index = pos / BLOCK
            val block = synchronized(blocks) { blocks[index] } ?: fetch(index).also { synchronized(blocks) { blocks[index] = it } }
            val within = (pos - index * BLOCK).toInt()
            if (within >= block.size) break
            val n = minOf(size - copied, block.size - within)
            System.arraycopy(block, within, buffer, offset + copied, n)
            copied += n
            pos += n
        }
        return if (copied == 0) -1 else copied
    }

    private fun fetch(index: Long): ByteArray {
        val start = index * BLOCK
        val end = minOf(start + BLOCK, length) - 1
        val request = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
        client.newCall(request).execute().use { response ->
            if (response.code != 206 && !(response.code == 200 && start == 0L)) {
                throw IOException("camera returned HTTP ${response.code} for a range request")
            }
            val bytes = response.body.byteStream().readNBytes((end - start + 1).toInt())
            return bytes
        }
    }

    override fun close() {
        synchronized(blocks) { blocks.clear() }
    }

    companion object {
        private const val BLOCK = 256L * 1024
        private const val MAX_BLOCKS = 24
    }
}
