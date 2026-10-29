package com.openpro.camera.http

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * The camera serves one stream at a time over its Wi-Fi: a batch download, a preview playing on a
 * clip's page, a full-size photo, the live preview. Its small web server can crash under more (two
 * video streams at once took a HERO 2024 down entirely). Whoever is streaming holds this; anyone
 * else waits and can say who for. First come, first served: the batch can be paused to free it.
 * Small requests (the media list, thumbnails, clip lengths) don't take part.
 */
object CameraStreams {
    enum class Who { Batch, Viewer }

    /** Who has the camera, and what for, as a phrase: "the batch is downloading GX010849.MP4". */
    data class Holder(val id: Long, val who: Who, val doing: String)

    private val ids = AtomicLong()
    private val _holder = MutableStateFlow<Holder?>(null)
    val holder: StateFlow<Holder?> = _holder

    /** Called whenever the camera is freed, so waiting batch jobs look again. Set by the app. */
    @Volatile var onFree: (() -> Unit)? = null

    /** Takes the camera if no one has it; null if someone does. */
    @Synchronized
    fun tryAcquire(who: Who, doing: String): Holder? {
        if (_holder.value != null) return null
        return Holder(ids.incrementAndGet(), who, doing).also { _holder.value = it }
    }

    @Synchronized
    fun release(holder: Holder) {
        if (_holder.value?.id != holder.id) return
        _holder.value = null
        onFree?.invoke()
    }
}
