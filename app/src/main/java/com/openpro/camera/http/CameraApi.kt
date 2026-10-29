package com.openpro.camera.http

import android.net.Uri
import com.openpro.media.CameraMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.openpro.camera.wifi.CameraRoute

/**
 * The camera's HTTP API on its own access point, using the same endpoints Quik uses
 * (com.gopro.wsdk ...Command classes): legacy gpControl paths that the HERO line still serves.
 */
class CameraApi(val route: CameraRoute) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(route.socketFactory)
        // Through Camera Link, URLs keep naming the camera (so caches and keys stay stable) and are
        // rewritten to the relay here; one request per connection, as the relay expects.
        .apply {
            if (route.relay != null) addInterceptor { chain ->
                val r = chain.request()
                chain.proceed(r.newBuilder().url(route.rewrite(r.url.toString())).header("Connection", "close").build())
            }
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        // At most CameraTraffic.MAX_CONNECTIONS transfers at once, metered for the activity bar.
        .addInterceptor(CameraTraffic.interceptor)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = CameraTraffic.MAX_CONNECTIONS })
        .build()

    suspend fun mediaList(): List<CameraMedia> = withContext(Dispatchers.IO) {
        parseMediaList(getString("gp/gpMediaList"))
    }

    /** Deletes one file from the camera's SD card (Open GoPro: GET /gopro/media/delete/file?path=…). */
    suspend fun deleteFile(media: CameraMedia) = withContext(Dispatchers.IO) {
        getString("gopro/media/delete/file?path=" + Uri.encode("${media.folder}/${media.name}", "/"))
        Unit
    }

    /** Raw v5info JSON (dimensions, fps, duration, eis, ...). */
    suspend fun mediaInfo(media: CameraMedia): JsonObject = withContext(Dispatchers.IO) {
        Json.parseToJsonElement(getString(metadataUrl(media, "v5info"))).jsonObject
    }

    /** Open GoPro media info (GET /gopro/media/info?path=…): carries "dur" and, on newer cameras, "msdur". */
    suspend fun openGoProMediaInfo(media: CameraMedia): JsonObject = withContext(Dispatchers.IO) {
        Json.parseToJsonElement(getString("gopro/media/info?path=" + Uri.encode("${media.folder}/${media.name}", "/"))).jsonObject
    }

    private fun getString(path: String): String {
        val request = Request.Builder().url(url(path)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("camera returned HTTP ${response.code} for $path")
            return response.body.string()
        }
    }

    companion object {
        const val HOST = "10.5.5.9"
        const val PORT = 8080
        private val json = Json { ignoreUnknownKeys = true }

        fun url(path: String) = "http://$HOST:$PORT/${path.trimStart('/')}"

        private fun p(media: CameraMedia) = Uri.encode("/${media.folder}/${media.name}", "/")

        /** Small JPEG thumbnail (ThumbnailUrlCommand / h1h). */
        fun thumbnailUrl(media: CameraMedia) = url("gp/gpMediaMetadata?p=${p(media)}")

        /** Larger "screennail" JPEG (ove: t=scr). */
        fun screennailUrl(media: CameraMedia) = url("gp/gpMediaMetadata?p=${p(media)}&t=scr")

        fun metadataUrl(media: CameraMedia, type: String) = url("gp/gpMediaMetadata?p=${p(media)}&t=$type")

        /** Telemetry (GPMF) track only, the resource Quik feeds the engine for camera clips (cqg). */
        fun telemetryUrl(media: CameraMedia) =
            url("gp/gpMediaMetadata?t=tracks&trackNames=telemetry&p=${p(media)}")

        /** Full-resolution original (VideoDownloadUrlCommand / tzh, source=true). */
        fun sourceUrl(media: CameraMedia) = url("videos/DCIM/${media.folder}/${media.name}")

        /** Low-res proxy (tzh, source=false). */
        fun lowResUrl(media: CameraMedia) = url("videos/DCIM/${media.folder}/${media.lrvName}")

        /** Parses gp/gpMediaList the way Quik's kua parser does. */
        fun parseMediaList(body: String): List<CameraMedia> {
            val root = json.parseToJsonElement(body).jsonObject
            val out = mutableListOf<CameraMedia>()
            for (dir in root["media"]?.jsonArray.orEmpty()) {
                val folder = dir.jsonObject["d"]?.jsonPrimitive?.content ?: continue
                for (file in dir.jsonObject["fs"]?.jsonArray.orEmpty()) {
                    val f = file.jsonObject
                    fun long(key: String) = f[key]?.jsonPrimitive?.let { it.longOrNull ?: it.content.toLongOrNull() }
                    val name = f["n"]?.jsonPrimitive?.content ?: continue
                    val lrv = long("glrv") ?: long("ls") ?: -1
                    out += CameraMedia(
                        folder = folder,
                        name = name,
                        sizeBytes = long("s") ?: -1,
                        createdSec = long("cre") ?: 0,
                        modifiedSec = long("mod") ?: 0,
                        lrvSizeBytes = if (lrv > 0) lrv else -1,
                        groupFirst = long("b")?.toInt(),
                        groupLast = long("l")?.toInt(),
                    )
                }
            }
            return out.sortedByDescending { it.createdSec }
        }
    }
}
