package com.openpro.quik

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * Single-clip edit list in Quik's format (QuikSingleClipInput, EDL version 0.30.0), serialized the
 * way Quik's kotlinx.serialization config does it: class discriminator "type", defaults omitted.
 */
object Edl {
    /** Current EDL version in Quik 13.43 (QuikVersion.Companion -> 0.30.0), encoded as an int array. */
    private val VERSION = listOf(0, 30, 0)

    const val PROVIDER_FILE = "file"
    const val PROVIDER_PLATFORM_URI = "platform_uri"

    fun singleClip(
        mediaProvider: String,
        mediaKey: String,
        timelineElementId: String,
        stabilization: JsonElement?,
        uuid: String = UUID.randomUUID().toString(),
    ): String = buildJsonObject {
        putJsonArray("version") { VERSION.forEach { add(it) } }
        put("uuid", uuid)
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "Video")
                put("timeline_element_id", timelineElementId)
                putJsonObject("media_id") {
                    put("provider", mediaProvider)
                    put("key", mediaKey)
                }
                if (stabilization != null) put("stabilization", stabilization)
            })
        }
    }.toString()

    /**
     * Stable per-clip timeline_element_id. The engine rejects edit lists whose IDs aren't RFC 4122
     * version-4 UUIDs (it reports a validation error), so a name hash gets the v4/variant bits stamped on.
     */
    fun uuid4For(name: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).copyOf(16)
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x40).toByte()
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()
        val buffer = java.nio.ByteBuffer.wrap(hash)
        return UUID(buffer.long, buffer.long).toString()
    }

    /** {"type":"AntiShake","horizon_leveling":bool}: Quik's in-app HyperSmooth. */
    fun antiShake(horizonLeveling: Boolean): JsonObject = buildJsonObject {
        put("type", "AntiShake")
        put("horizon_leveling", horizonLeveling)
    }

    /** {"type":"HorizonLeveling"}: level only, from the camera's CORI/GRAV data (HERO8+). */
    fun horizonLeveling(): JsonObject = buildJsonObject { put("type", "HorizonLeveling") }

    /**
     * 360 clips (EDL schema `spherical_stabilization`): {"type":"SphericalStabilization",
     * "anti_shake_intensity":0..1, "settings":{"type":"Custom","horizon_leveling":bool,"world_lock":bool}}.
     * The engine's StabilizerTask computes the rotations itself. 0.5 is the schema's default intensity.
     */
    fun spherical(worldLock: Boolean, horizonLeveling: Boolean = true, intensity: Double = 0.5): JsonObject = buildJsonObject {
        put("type", "SphericalStabilization")
        put("anti_shake_intensity", intensity)
        putJsonObject("settings") {
            put("type", "Custom")
            put("horizon_leveling", horizonLeveling)
            put("world_lock", worldLock)
        }
    }
}
