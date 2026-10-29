package com.openpro.media

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
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException

/** Coil model for a photo in full: what the clip page shows instead of a video player. */
data class CameraPhoto(val media: CameraMedia)

/**
 * A photo at full quality: the phone's copy if there is one, else the camera's file, downloaded
 * into cacheDir/photos only while its page is open ([forget] when it closes): keeping a photo is
 * what downloading it is for. GoPro's raw GPR files can't be decoded on Android, so for those it's
 * the camera's larger preview JPEG ("screennail").
 */
class CameraPhotoFetcher(private val data: CameraPhoto, private val options: Options, private val app: OpenProApp) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val media = data.media
        phoneCopy(media)?.let { uri ->
            app.contentResolver.openInputStream(uri)?.let { stream ->
                return@withContext SourceFetchResult(ImageSource(stream.source().buffer(), options.fileSystem), "image/jpeg", DataSource.DISK)
            }
        }
        val file = cached(media) ?: download(media)
        SourceFetchResult(ImageSource(file.toOkioPath(), options.fileSystem), "image/jpeg", DataSource.DISK)
    }

    private fun isJpeg(media: CameraMedia) = media.extension == "JPG" || media.extension == "JPEG"

    private fun phoneCopy(media: CameraMedia): android.net.Uri? =
        if (isJpeg(media)) app.copies.phoneUri(media, ClipVersion.Original) else null

    private fun cached(media: CameraMedia): File? = cacheFile(app.cacheDir, media).takeIf { it.length() > 0 }

    private fun download(media: CameraMedia): File {
        val api = app.camera.api?.takeIf { app.catalogue.onConnectedCamera(media) }
            ?: throw IOException(if (app.catalogue[media.id]?.onCamera == false) "Not on the camera any more, and no copy on this phone" else "Connect to the camera's Wi-Fi to see this photo")
        val url = if (isJpeg(media)) CameraApi.sourceUrl(media) else CameraApi.screennailUrl(media)
        val target = cacheFile(app.cacheDir, media)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        try {
            api.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("The camera answered HTTP ${r.code} for ${media.name}")
                part.outputStream().use { out -> r.body.byteStream().copyTo(out) }
            }
            if (!part.renameTo(target)) throw IOException("Couldn't keep ${media.name}")
        } catch (e: IOException) {
            part.delete()
            Log.w(TAG, "photo ${media.name}", e)
            throw e
        }
        return target
    }

    class Factory(private val app: OpenProApp) : Fetcher.Factory<CameraPhoto> {
        override fun create(data: CameraPhoto, options: Options, imageLoader: ImageLoader): Fetcher = CameraPhotoFetcher(data, options, app)
    }

    class Key : Keyer<CameraPhoto> {
        override fun key(data: CameraPhoto, options: Options) = "photo:${data.media.id}"
    }

    companion object {
        private const val TAG = "CameraPhotos"

        private fun cacheFile(cacheDir: File, media: CameraMedia) =
            File(cacheDir, "photos/${ClipNames.workName(media).substringBeforeLast('.')}.jpg")

        /** Drops the photo fetched for a page that has closed. */
        fun forget(cacheDir: File, media: CameraMedia) {
            cacheFile(cacheDir, media).delete()
        }

        /** Drops any photo left over (e.g. the app was closed while a photo was open). */
        fun clearAll(cacheDir: File) {
            File(cacheDir, "photos").deleteRecursively()
        }
    }
}
