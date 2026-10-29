package com.openpro.quik

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How to stabilize: what Quik would pick for this clip, a specific mode, or nothing. [shortLabel]
 * names it in lists of copies; [slug] goes into file names, so copies made different ways can sit
 * side by side ("…_stabilized-hypersmooth.mp4"). [for360] marks the kinds for 360 clips (MAX2
 * `.360` files) and the rest are for flat ones; "GoPro default" fits both.
 */
@Serializable
enum class StabilizationChoice(val label: String, val shortLabel: String, val slug: String, val for360: Boolean? = false) {
    Suggested("GoPro default", "GoPro default", "default", for360 = null),
    AntiShake("Stabilize (HyperSmooth)", "HyperSmooth", "hypersmooth"),
    AntiShakeLevel("Stabilize + level horizon", "HyperSmooth + level horizon", "hypersmooth-level"),
    HorizonLeveling("Level horizon only", "Level horizon only", "level"),
    /** 360: smooths the shake and levels the horizon; the view still turns as the camera does. */
    Spherical("Stabilize 360, level horizon", "360 · level horizon", "360-level", for360 = true),
    /** 360: also locks the view to the world, so it doesn't turn with the camera. */
    SphericalWorldLock("Stabilize 360, world lock", "360 · world lock", "360-worldlock", for360 = true),
    Off("Off", "No stabilization", "none", for360 = null);

    /** Whether this kind can be used on a clip that is ([is360]) or isn't a 360 clip. */
    fun fits(is360: Boolean) = for360 == null || for360 == is360
}

@Serializable
enum class JobKind { Preview, Export }

/** Where the engine reads the clip from. Exactly one of camera/localPath is set. */
@Serializable
data class JobSource(
    val camera: CameraSource? = null,
    val localPath: String? = null,
)

@Serializable
data class CameraSource(
    val folder: String,
    val name: String,
    val sourceUrl: String,
    val lowResUrl: String?,
    val telemetryUrl: String,
)

@Serializable
data class QuikJob(
    val id: String,
    val kind: JobKind,
    val source: JobSource,
    val stabilization: StabilizationChoice,
    val outputPath: String,
    /** Preview only: feed the LRV as the source so nothing full-size crosses WiFi. */
    val proxyOnlyPreview: Boolean = false,
    /** A 360 clip (the camera's file is a `.360`), whatever the local copy happens to be named. */
    val is360: Boolean = false,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(text: String): QuikJob = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class QuikResult(
    val jobId: String,
    val outputPath: String? = null,
    val error: String? = null,
    val appliedStabilization: String? = null,
    val possibleStabilizations: List<String> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
)
