package com.openpro.copies

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.openpro.media.CameraMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A clip OpenPro has seen in a camera's listing: enough to show it without the camera, which
 * camera it came from, and whether it was still on that camera when it was last listed.
 */
@Entity(tableName = "clips")
data class ClipEntity(
    /** [CameraMedia.id]. */
    @PrimaryKey val id: String,
    val folder: String,
    val name: String,
    val sizeBytes: Long,
    val createdSec: Long,
    val modifiedSec: Long,
    val lrvSizeBytes: Long,
    val groupFirst: Int?,
    val groupLast: Int?,
    /** Serial number of the camera it was listed on, when known. */
    val cameraSerial: String?,
    /** In that camera's latest listing (false once it's deleted there or the card is formatted). */
    val onCamera: Boolean,
    /** When the camera last listed it. */
    val lastSeenAt: Long,
) {
    fun toMedia() = CameraMedia(folder, name, sizeBytes, createdSec, modifiedSec, lrvSizeBytes, groupFirst, groupLast)

    companion object {
        fun of(media: CameraMedia, serial: String?, now: Long) = ClipEntity(
            media.id, media.folder, media.name, media.sizeBytes, media.createdSec, media.modifiedSec, media.lrvSizeBytes,
            media.groupFirst, media.groupLast, serial, onCamera = true, lastSeenAt = now,
        )
    }
}

@Dao
abstract class ClipDao {
    @Query("SELECT * FROM clips")
    abstract fun observe(): Flow<List<ClipEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putAll(clips: List<ClipEntity>)

    @Query("UPDATE clips SET onCamera = 0 WHERE id IN (:ids)")
    abstract suspend fun leftCamera(ids: List<String>)
}

/**
 * Every clip OpenPro has seen on a camera, so the gallery can show them without one: each
 * camera's listing updates it, and a clip deleted on the camera stays listed (no longer on the
 * camera) for as long as it has copies elsewhere. Kept in memory ([clips]) like the copies index.
 * [connectedSerial] is the serial number of the camera connected now, if any.
 */
class ClipCatalogue(db: CopyDb, private val scope: CoroutineScope, private val connectedSerial: () -> String?) {
    private val dao = db.clips()
    private val _clips = MutableStateFlow<Map<String, ClipEntity>>(emptyMap())
    val clips: StateFlow<Map<String, ClipEntity>> = _clips

    init {
        scope.launch(Dispatchers.IO) { dao.observe().collect { all -> _clips.value = all.associateBy { it.id } } }
    }

    operator fun get(id: String): ClipEntity? = _clips.value[id]

    /**
     * A camera's listing: its clips are recorded as on it, and its clips missing from the listing
     * as gone from it. Applied in memory at once, so the gallery doesn't wait for the database.
     */
    fun listed(serial: String?, media: List<CameraMedia>) {
        val now = System.currentTimeMillis()
        val fresh = media.map { ClipEntity.of(it, serial, now) }
        val ids = fresh.map { it.id }.toSet()
        // Without a serial number, which clips this camera no longer has can't be told.
        val gone = if (serial == null) emptyList() else _clips.value.values.filter { it.cameraSerial == serial && it.onCamera && it.id !in ids }
        _clips.value = _clips.value + fresh.associateBy { it.id } + gone.associate { it.id to it.copy(onCamera = false) }
        scope.launch(Dispatchers.IO) {
            dao.putAll(fresh)
            if (gone.isNotEmpty()) dao.leftCamera(gone.map { it.id })
        }
    }

    /** [id] was deleted from its camera. */
    fun leftCamera(id: String) {
        val clip = _clips.value[id] ?: return
        _clips.value = _clips.value + (id to clip.copy(onCamera = false))
        scope.launch(Dispatchers.IO) { dao.leftCamera(listOf(id)) }
    }

    /**
     * Whether [media] can be read from the camera connected now: it's on that camera, as far as
     * OpenPro knows. Stops another camera's file of the same name being shown or fetched in its place.
     */
    fun onConnectedCamera(media: CameraMedia): Boolean {
        val clip = _clips.value[media.id] ?: return true
        if (!clip.onCamera) return false
        val serial = connectedSerial()
        return clip.cameraSerial == null || serial == null || clip.cameraSerial == serial
    }
}
