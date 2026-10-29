package com.openpro.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.openpro.OpenProApp
import com.openpro.camera.http.CameraApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File

/** How sharp gallery thumbnails are, and so how much space their cache takes on the phone. */
enum class ThumbQuality(val label: String, val width: Int, val height: Int, val jpegQuality: Int) {
    Low("Low", 320, 180, 70),
    High("High", 640, 360, 88),
}

/** Coil model for a camera clip's thumbnail. [online] is part of the key so tiles retry once WiFi is up. */
data class CameraThumb(val media: CameraMedia, val online: Boolean, val quality: ThumbQuality = ThumbQuality.Low)

/**
 * Thumbnail for every clip, in order of cost:
 *  1. the camera's own thumbnail (gp/gpMediaMetadata?p=…), if it returns a real JPEG;
 *  2. a frame grabbed from the downloaded original, if it's on the phone;
 *  3. a frame grabbed from the low-res proxy (LRV) over HTTP range requests;
 *  4. a frame grabbed from the original on the camera the same way.
 * The camera's thumbnail is small, so [ThumbQuality.High] tries the frame grabs first and only
 * falls back to it. Results are kept as JPEGs in cacheDir/thumbs/<quality> if the user keeps that
 * quality (Storage page); otherwise only in memory while the app runs.
 */
class CameraThumbFetcher(private val data: CameraThumb, private val options: Options, private val app: OpenProApp) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val media = data.media
        val cached = cacheFile(media)
        val bytes = cached.takeIf { it.length() > 0 }?.readBytes()
            ?: produceOnce(media, cached)
            ?: return@withContext null
        SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    /**
     * Only one fetch per clip at a time, and a clip that failed while the camera was reachable is
     * not retried until the user refreshes ([clearFailures]); otherwise every re-layout (grid size,
     * list/grid switch) would go back to the camera.
     */
    private suspend fun produceOnce(media: CameraMedia, cached: File): ByteArray? {
        val key = "${media.id}:$quality"
        if (key in failed) return null
        val online = cameraApi(media) != null
        val job = inFlight.computeIfAbsent(key) {
            fetchScope.async {
                produce(media)?.also {
                    // Kept only if the user keeps thumbnails of this quality (Storage page).
                    if (quality in app.storage.keptThumbs.value) {
                        cached.parentFile?.mkdirs()
                        cached.writeBytes(it)
                    }
                }
            }
        }
        return try {
            job.await().also { if (it == null && online) failed += key }
        } finally {
            inFlight.remove(key, job)
        }
    }

    private val quality get() = data.quality

    private fun cacheFile(media: CameraMedia) =
        File(app.storage.thumbDir(quality), "${ClipNames.workName(media)}_${media.modifiedSec}.jpg")

    /** The camera, if [media] is on the one connected (another camera may have a file of the same name). */
    private fun cameraApi(media: CameraMedia): CameraApi? = app.camera.api?.takeIf { app.catalogue.onConnectedCamera(media) }

    private suspend fun produce(media: CameraMedia): ByteArray? {
        val api = cameraApi(media)
        if (quality == ThumbQuality.High) {
            return grabbed(media, api) ?: api?.let { cameraThumbnail(it, media) }
        }
        api?.let { cameraThumbnail(it, media) }?.let { return it }
        return grabbed(media, api)
    }

    private suspend fun grabbed(media: CameraMedia, api: CameraApi?): ByteArray? {
        // A copy on the phone (the original, else the preview) works without the camera.
        (app.copies.phoneUri(media, ClipVersion.Original) ?: app.copies.phoneUri(media, ClipVersion.Preview))?.let { uri ->
            frameGrabs.withPermit { grab(media) { setDataSource(app, uri) } }?.let { return it }
        }
        if (api == null || !media.isVideo) return null
        if (media.hasLrv) {
            frameGrabs.withPermit {
                grab(media) { setDataSource(HttpMediaDataSource(api.client, CameraApi.lowResUrl(media), media.lrvSizeBytes)) }
            }?.let { return it }
        }
        if (media.sizeBytes > 0) {
            return frameGrabs.withPermit {
                grab(media) { setDataSource(HttpMediaDataSource(api.client, CameraApi.sourceUrl(media), media.sizeBytes)) }
            }
        }
        return null
    }

    private fun cameraThumbnail(api: CameraApi, media: CameraMedia): ByteArray? = runCatching {
        api.client.newCall(Request.Builder().url(CameraApi.thumbnailUrl(media)).build()).execute().use { r ->
            val body = if (r.isSuccessful) r.body.bytes() else null
            // Some clips (chapters, unusual modes) get an empty or non-JPEG answer.
            body?.takeIf { it.size > 3 && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }?.let(::fitted)
        }
    }.onFailure { Log.w(TAG, "camera thumbnail for ${media.name}", it) }.getOrNull()

    /** The camera's JPEG as it is if it's no wider than [quality] asks for, else scaled down. */
    private fun fitted(jpeg: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= quality.width) return jpeg
        val full = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val scaled = Bitmap.createScaledBitmap(full, quality.width, full.height * quality.width / full.width, true)
        return compress(scaled)
    }

    private fun compress(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, out)
        out.toByteArray()
    }

    private fun grab(media: CameraMedia, open: MediaMetadataRetriever.() -> Unit): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.open()
            val frame = retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, quality.width, quality.height)
                ?: retriever.getFrameAtTime(0)?.let { Bitmap.createScaledBitmap(it, quality.width, it.height * quality.width / it.width, true) }
                ?: return@runCatching null
            compress(frame)
        } finally {
            retriever.release()
        }
    }.onFailure { Log.w(TAG, "frame grab for ${media.name}", it) }.getOrNull()

    class Factory(private val app: OpenProApp) : Fetcher.Factory<CameraThumb> {
        override fun create(data: CameraThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            CameraThumbFetcher(data, options, app)
    }

    class Key : Keyer<CameraThumb> {
        override fun key(data: CameraThumb, options: Options) =
            "thumb:${data.media.id}:${data.media.modifiedSec}:${data.online}:${data.quality}"
    }

    companion object {
        private const val TAG = "CameraThumbs"

        private val fetchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ByteArray?>>()
        private val failed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** Called on Refresh: give clips whose thumbnail failed another try. */
        fun clearFailures() = failed.clear()

        /** Frame grabs pull data over the camera's WiFi; one at a time to spare the camera. */
        private val frameGrabs = Semaphore(1)
    }
}
