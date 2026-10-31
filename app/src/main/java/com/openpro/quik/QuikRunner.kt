package com.openpro.quik

import android.content.Context
import android.util.Log
import com.gopro.quikengine.Exporter
import com.gopro.quikengine.OutputProjection
import com.gopro.quikengine.QuikEngine
import com.gopro.quikengine.model.EngineError
import com.gopro.quikengine.model.PerformanceError
import com.gopro.quikengine.model.PythonError
import com.gopro.quikengine.model.VideoCodec
import com.gopro.quikengine.services.AssetStore
import com.gopro.quikengine.services.PythonEngine
import com.gopro.quikengine.services.PythonInterpreter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Output settings chosen from the engine's own get_export_settings answer. */
data class ExportSetting(
    val width: Int, val height: Int, val codec: VideoCodec, val bitrate: Int, val framerate: String, val score: Double = 0.0,
) {
    val roundedFps: Int get() = framerate.split('/').let { p ->
        val n = p.getOrNull(0)?.toDoubleOrNull() ?: return 0
        val d = p.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        kotlin.math.round(n / d).toInt()
    }
}

class QuikException(message: String) : Exception(message) {
    companion object {
        /**
         * Starts the error of a clip the engine can't stabilize the way asked. Errors reach the app
         * as text (the engine runs in its own process), so the batch recognises these by this and
         * doesn't retry them.
         */
        const val UNSUPPORTED = "Can't stabilize this clip: "
    }
}

/**
 * Drives GoPro's engine through the same calls Quik makes for a single clip:
 *  1. getMediaFeatures(edl, [id], ["suggested_stabilization_settings", "possible_stabilizations"])
 *  2. get_export_settings(edl, projection) in director.api.mobile: "PLANAR", or "ERP" for 360 clips
 *  3. Exporter.start(output, edl, w, h, bitrate, codec, projection, fps, 0, 0)
 * Must run on one thread in the :quik process.
 */
class QuikRunner(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private var store: HeroAssetStore? = null

    fun ensureLoaded(): HeroAssetStore {
        store?.let { return it }
        // The engine is loaded from where it was added, in the app's storage.
        EngineFiles.prepare(context)
        if (!QuikEngine.loadBlocking(context)) throw QuikException("Engine data bundle could not be installed")
        Log.i(TAG, "engine ${QuikEngine.getBuildNumber()} loaded from ${QuikEngine.getBundlePath()}")
        val s = HeroAssetStore(File(context.filesDir, "asset_registry.json"))
        AssetStore.set(s)
        store = s
        return s
    }

    fun run(job: QuikJob, onProgress: (Float, String) -> Unit, cancelled: () -> Boolean): QuikResult {
        val store = ensureLoaded()
        val key: String
        val provider: String
        when {
            job.source.camera != null -> {
                val cam = job.source.camera
                key = "camera/${cam.folder}/${cam.name}" + if (job.proxyOnlyPreview) "#lrv" else ""
                provider = Edl.PROVIDER_PLATFORM_URI
                store.putCameraClip(key, cam, job.proxyOnlyPreview && job.kind == JobKind.Preview)
            }
            job.source.localPath != null -> {
                key = job.source.localPath
                provider = Edl.PROVIDER_FILE
                store.putLocalFile(key, job.source.localPath)
            }
            else -> throw QuikException("job has no source")
        }
        val elementId = Edl.uuid4For(key)
        // 360 clips (MAX2 .360 files) stabilize with the engine's spherical kind and export as
        // 360 video (equirectangular), not as flat video.
        val is360 = job.is360 || (job.source.localPath ?: job.source.camera?.name).orEmpty().endsWith(".360", ignoreCase = true)
        if (!job.stabilization.fits(is360)) {
            throw QuikException(
                QuikException.UNSUPPORTED +
                    if (is360) "it's a 360 clip: use GoPro default or a 360 kind" else "360 stabilization is only for 360 clips",
            )
        }
        val projection = if (is360) OutputProjection.ERP else OutputProjection.PLANAR

        onProgress(0f, "Asking the engine how to stabilize this clip…")
        val bare = Edl.singleClip(provider, key, elementId, null)
        val features = mediaFeatures(bare, elementId)
        val suggested = features?.get("suggested_stabilization_settings")?.takeUnless { it is JsonPrimitive }
        val possible = (features?.get("possible_stabilizations") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull }
        Log.i(TAG, "suggested=$suggested possible=$possible")

        val stabilization: JsonElement? = when (job.stabilization) {
            StabilizationChoice.Suggested -> suggested
                // Without this, a clip the engine has no suggestion for came out unstabilized but named as if it were.
                ?: throw QuikException(
                    QuikException.UNSUPPORTED + "GoPro's engine has no suggestion for it" +
                        if (possible.isEmpty()) "" else "; it offers: ${possible.joinToString()}",
                )
            StabilizationChoice.AntiShake -> Edl.antiShake(false)
            StabilizationChoice.AntiShakeLevel -> Edl.antiShake(true)
            StabilizationChoice.HorizonLeveling -> Edl.horizonLeveling()
            StabilizationChoice.Spherical -> Edl.spherical(worldLock = false)
            StabilizationChoice.SphericalWorldLock -> Edl.spherical(worldLock = true)
            StabilizationChoice.Off -> null
        }
        // The engine says per clip (from its metadata, so per camera model) what it can do; when it
        // does, refuse a choice outside that rather than render something wrong.
        (stabilization as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull?.let { type ->
            if (possible.isNotEmpty() && type !in possible) {
                throw QuikException(QuikException.UNSUPPORTED + "GoPro's engine doesn't offer $type for it; it offers: ${possible.joinToString()}")
            }
        }
        val edl = Edl.singleClip(provider, key, elementId, stabilization)

        onProgress(0f, "Choosing output settings…")
        val setting = chooseSetting(exportSettings(edl, projection), job.kind, is360)
        Log.i(TAG, "export $projection $setting -> ${job.outputPath}")

        File(job.outputPath).parentFile?.mkdirs()
        export(edl, job.outputPath, setting, projection, onProgress, cancelled)
        return QuikResult(
            jobId = job.id,
            outputPath = job.outputPath,
            appliedStabilization = stabilization?.toString() ?: "none",
            possibleStabilizations = possible,
            width = setting.width,
            height = setting.height,
        )
    }

    private fun mediaFeatures(edl: String, elementId: String): JsonObject? {
        val result = pythonCall { listener ->
            PythonEngine.getMediaFeatures(listener, edl, "[\"$elementId\"]",
                "[\"suggested_stabilization_settings\",\"possible_stabilizations\"]")
        }
        val parsed = runCatching { json.parseToJsonElement(result) }.getOrNull() ?: return null
        val list = parsed as? JsonArray ?: return parsed as? JsonObject
        return list.firstOrNull { (it as? JsonObject)?.get("timeline_element_id")?.jsonPrimitive?.contentOrNull == elementId }
            as? JsonObject ?: list.firstOrNull() as? JsonObject
    }

    /** The engine's output options for [edl] in [projection] (360 clips have their own resolutions). */
    private fun exportSettings(edl: String, projection: OutputProjection): JsonObject {
        val result = pythonCall { listener ->
            PythonInterpreter.executeFunction("get_export_settings", "director.api.mobile",
                object : PythonInterpreter.Listener {
                    override fun onPythonFailed(error: PythonError) = listener.onPythonFailed(error)
                    override fun onPythonSucceeded(result: PythonInterpreter.PythonObject) = listener.onPythonSucceeded(result)
                }, edl, projection.name)
        }
        return json.parseToJsonElement(result).jsonObject
    }

    /** Waits for a Python listener callback and returns the result's JSON string. */
    private fun pythonCall(start: (PythonEngine.Listener) -> Unit): String {
        val latch = CountDownLatch(1)
        var value: String? = null
        var failure: String? = null
        start(object : PythonEngine.Listener {
            override fun onPythonFailed(error: PythonError) {
                failure = "${error.function}: ${error.message}"
                Log.w(TAG, "python failed: $error")
                latch.countDown()
            }

            override fun onPythonSucceeded(result: PythonInterpreter.PythonObject) {
                if (result.isValid) value = result.string else failure = "invalid Python result"
                latch.countDown()
            }
        })
        if (!latch.await(120, TimeUnit.SECONDS)) throw QuikException("engine did not answer")
        return value ?: throw QuikException(failure ?: "engine call failed")
    }

    /**
     * Picks from the engine's get_export_settings answer ({settings, presets, source_settings}).
     * Export: the setting identical to the source when offered (Quik's "source" preset), else Quik's
     * default (QuikVideoExportParamsDto): short side ~1080, fps rounding to 30 or 25, H.264, lowest
     * bitrate among those; falling back to the highest-score setting. A 360 export ([is360]) takes
     * the engine's highest-score setting: 360 video spreads its pixels over the whole sphere, so the
     * flat ~1080 default would look soft, and the source (packed differently) is never offered.
     */
    private fun chooseSetting(params: JsonObject, kind: JobKind, is360: Boolean = false): ExportSetting {
        val settings = params["settings"]?.jsonArray.orEmpty().mapNotNull { parseSetting(it) }
        if (settings.isEmpty()) throw QuikException("engine offered no export settings: $params")
        if (kind == JobKind.Preview) {
            // Smallest rendition at least 360 lines on its short side, so previews stay a few MB.
            return settings.filter { minOf(it.width, it.height) >= 360 }.minByOrNull { it.width * it.height }
                ?: settings.minBy { it.width * it.height }
        }
        val best = settings.maxBy { it.score }
        if (is360) return best
        params["source_settings"]?.let { parseSetting(it) }?.let { source ->
            settings.firstOrNull {
                ((it.width == source.width && it.height == source.height) || (it.width == source.height && it.height == source.width)) &&
                    it.framerate == source.framerate && it.bitrate == source.bitrate && it.codec == source.codec
            }?.let { return it }
        }
        val resolution = settings.firstOrNull { kotlin.math.abs(minOf(it.width, it.height) - 1080) < 5 }?.let { it.width to it.height }
            ?: (best.width to best.height)
        val framerate = settings.firstOrNull { it.roundedFps == 30 || it.roundedFps == 25 }?.framerate ?: best.framerate
        val codec = if (settings.any { it.codec == VideoCodec.H264 }) VideoCodec.H264 else best.codec
        return settings.filter { (it.width to it.height) == resolution && it.framerate == framerate && it.codec == codec }
            .minByOrNull { it.bitrate } ?: best
    }

    private fun parseSetting(element: JsonElement): ExportSetting? {
        val o = element as? JsonObject ?: return null
        val (w, h) = when (val r = o["resolution"]) {
            is JsonArray -> r[0].jsonPrimitive.intOrNull to r[1].jsonPrimitive.intOrNull
            is JsonObject -> r["width"]?.jsonPrimitive?.intOrNull to r["height"]?.jsonPrimitive?.intOrNull
            is JsonPrimitive -> r.content.split('x', 'X').let { it.getOrNull(0)?.toIntOrNull() to it.getOrNull(1)?.toIntOrNull() }
            else -> null to null
        }
        if (w == null || h == null) return null
        val codecText = o["codec"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        val codec = if ("265" in codecText || "hevc" in codecText) VideoCodec.HEVC else VideoCodec.H264
        val bitrate = o["bitrate"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: (w * h * 8)
        val fps = when (val f = o["fps"]) {
            is JsonArray -> "${f[0].jsonPrimitive.content}/${f[1].jsonPrimitive.content}"
            is JsonObject -> "${f.values.first().jsonPrimitive.content}/${f.values.last().jsonPrimitive.content}"
            is JsonPrimitive -> f.content.let { if ('/' in it) it else (it.toDoubleOrNull()?.let { v -> "${(v * 1000).toInt()}/1000" } ?: "") }
            else -> ""
        }
        val score = o["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        return ExportSetting(w, h, codec, bitrate, fps, score)
    }

    /**
     * Runs the export. Progress reaches 100% when the last frame is rendered, but the engine then
     * still finishes the file (drains the encoder, writes the MP4's index), which for a long clip
     * takes a while with no progress callbacks. So the file's growth is reported throughout,
     * "Finishing" after 100%: that shows it's working, and keeps the service's stall watchdog from
     * mistaking a long finish for a hang (a real hang stops the file growing).
     */
    private fun export(
        edl: String, output: String, s: ExportSetting, projection: OutputProjection, report: (Float, String) -> Unit, cancelled: () -> Boolean,
    ) {
        val latch = CountDownLatch(1)
        var error: String? = null
        val file = File(output)
        val rendered = java.util.concurrent.atomic.AtomicBoolean(false)
        var lastSize = -1L
        val exporter = Exporter()
        exporter.setListener(object : Exporter.Listener {
            override fun onCanceled() {
                error = "cancelled"
                latch.countDown()
            }

            override fun onError(engineError: EngineError) {
                error = engineError.message
                Log.e(TAG, "export error: $engineError")
                latch.countDown()
            }

            override fun onInformationAlert(informationAlert: String) {
                Log.i(TAG, "info: $informationAlert")
            }

            override fun onPerformanceAlert(performanceError: PerformanceError) {
                Log.w(TAG, "perf: $performanceError")
            }

            override fun onProgress(progress: Float) {
                if (progress >= 0.999f) rendered.set(true)
                report(progress, "Rendering ${s.width}×${s.height}")
            }

            override fun onSuccess(filename: String) = latch.countDown()
        })
        try {
            if (!exporter.start(output, edl, s.width, s.height, s.bitrate, s.codec, projection, s.framerate, 0, 0)) {
                throw QuikException("engine refused to start the export")
            }
            while (!latch.await(500, TimeUnit.MILLISECONDS)) {
                if (cancelled()) exporter.cancel()
                if (rendered.get()) {
                    val size = file.length()
                    // Still growing: worth a report, which also tells the watchdog it hasn't hung.
                    if (size != lastSize) {
                        lastSize = size
                        report(1f, "Finishing the stabilized file…")
                    }
                }
            }
            error?.let { throw QuikException(it) }
        } finally {
            exporter.release()
        }
    }

    companion object {
        private const val TAG = "QuikRunner"
    }
}
