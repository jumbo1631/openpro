package com.openpro.storage

import com.openpro.camera.http.CameraApi
import com.openpro.media.CameraMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class Transfer(val mediaId: String, val bytes: Long, val total: Long, val error: String? = null) {
    val fraction get() = if (total > 0) bytes.toFloat() / total else 0f
}

/** Downloads originals from the camera, resuming partial files with HTTP Range requests. */
class Downloader(private val storage: Storage) {
    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    val transfers: StateFlow<Map<String, Transfer>> = _transfers

    /**
     * Downloads [media] (or with [preview], its low-res proxy) to [target] (default: the app's
     * originals folder), resuming a previous partial ".part" file with a Range request.
     * [onProgress] gets (bytes so far, total).
     */
    suspend fun download(
        api: CameraApi,
        media: CameraMedia,
        target: File = storage.originalFile(media),
        onProgress: ((Long, Long) -> Unit)? = null,
        preview: Boolean = false,
    ): File = withContext(Dispatchers.IO) {
        val size = if (preview) media.lrvSizeBytes else media.sizeBytes
        if (target.exists() && (size <= 0 || target.length() == size)) return@withContext target
        val part = File(target.path + ".part")
        try {
            var offset = if (part.exists()) part.length() else 0L
            val request = Request.Builder().url(if (preview) CameraApi.lowResUrl(media) else CameraApi.sourceUrl(media))
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                .build()
            api.client.newCall(request).execute().use { response ->
                if (response.code == 200 && offset > 0) offset = 0 // camera ignored Range; start over
                if (!response.isSuccessful) throw com.openpro.batch.HttpStatusException(response.code, "Camera")
                val total = if (size > 0) size else offset + response.body.contentLength()
                FileOutputStream(part, offset > 0).use { out ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(1 shl 20)
                        var written = offset
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            if (written - lastReport > (1 shl 20)) {
                                lastReport = written
                                _transfers.update { it + (media.id to Transfer(media.id, written, total)) }
                                onProgress?.invoke(written, total)
                            }
                        }
                    }
                }
            }
            if (size > 0 && part.length() != size) {
                throw IOException("incomplete download (${part.length()} of $size bytes); retry to resume")
            }
            if (!part.renameTo(target)) throw IOException("could not finalize ${target.name}")
            if (target.parentFile?.name == "originals") storage.recordOriginal(media, target)
            _transfers.update { it - media.id }
            target
        } catch (e: Exception) {
            _transfers.update { it + (media.id to Transfer(media.id, part.length(), size, e.message ?: "failed")) }
            throw e
        }
    }

    fun clearError(mediaId: String) = _transfers.update { it - mediaId }
}
