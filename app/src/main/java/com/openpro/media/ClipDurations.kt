package com.openpro.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.openpro.camera.CameraSession
import com.openpro.camera.CameraState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Video lengths for the gallery. The camera's media list doesn't include them, so each clip's is
 * looked up as its tile appears: from the phone's copy if there is one (no camera needed), else
 * from the camera's media info, at most [PARALLEL] at once (the camera's web server copes badly
 * with many requests). They're kept on the phone by clip id ([CameraMedia.id]) so each clip is
 * asked about once, and lengths still show while the camera is offline.
 */
class ClipDurations(
    private val context: Context,
    private val camera: CameraSession,
    private val scope: CoroutineScope,
    /** Whether the clip is on the camera connected now, so another camera's file of the same name isn't asked. */
    private val onConnectedCamera: (CameraMedia) -> Boolean,
    private val phoneCopy: (CameraMedia) -> android.net.Uri?,
) {
    private val prefs = context.getSharedPreferences("clip_durations", Context.MODE_PRIVATE)
    private val _known = MutableStateFlow(prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap())
    /** Length in milliseconds by clip id. */
    val known: StateFlow<Map<String, Long>> = _known
    private val asked = mutableSetOf<String>()
    private val gate = Semaphore(PARALLEL)

    /** Looks up [media]'s length if it isn't known yet. */
    fun request(media: CameraMedia) {
        if (!media.isVideo || media.id in _known.value) return
        synchronized(asked) { if (!asked.add(media.id)) return }
        scope.launch(Dispatchers.IO) {
            val ms = phoneCopy(media)?.let(::fileLengthMs) ?: fromCamera(media)
            if (ms == null) {
                synchronized(asked) { asked.remove(media.id) } // try again when the tile comes back
                return@launch
            }
            prefs.edit().putLong(media.id, ms).apply()
            _known.update { it + (media.id to ms) }
        }
    }

    /** Open GoPro's media info first, then the older v5info metadata that some firmware still serves. */
    private suspend fun fromCamera(media: CameraMedia): Long? {
        if (camera.state.value !is CameraState.Online || !onConnectedCamera(media)) return null
        val api = camera.api ?: return null
        return gate.withPermit {
            runCatching { lengthMs(api.openGoProMediaInfo(media)) }
                .onFailure { Log.i(TAG, "media info for ${media.name}: $it") }.getOrNull()
                ?: runCatching { lengthMs(api.mediaInfo(media)) }
                    .onFailure { Log.i(TAG, "v5info for ${media.name}: $it") }.getOrNull()
        }.also { if (it == null) Log.w(TAG, "no length for ${media.name} from the camera") }
    }

    private fun fileLengthMs(uri: android.net.Uri): Long? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } finally {
            r.release()
        }
    }.onFailure { Log.i(TAG, "length of $uri: $it") }.getOrNull()

    companion object {
        private const val TAG = "ClipDurations"
        private const val PARALLEL = 2

        /** Media info gives "msdur" (milliseconds) or "dur" (seconds), as strings or numbers. */
        fun lengthMs(info: JsonObject): Long? =
            info["msdur"]?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it > 0 }
                ?: info["dur"]?.jsonPrimitive?.content?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() }

        /** "0:42", "12:05", "1:02:03": how video players show a length. */
        fun format(ms: Long): String {
            val s = (ms + 500) / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
