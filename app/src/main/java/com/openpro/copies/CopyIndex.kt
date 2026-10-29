package com.openpro.copies

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.openpro.batch.JobVariant
import com.openpro.media.CameraMedia
import com.openpro.media.ClipVersion
import com.openpro.quik.StabilizationChoice
import com.openpro.storage.Storage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A readable file of [length] bytes, and what to close once done with it (an open descriptor), if anything. */
class OpenFile(val file: File, val length: Long, private val handle: java.io.Closeable?) : java.io.Closeable {
    override fun close() {
        handle?.close()
    }
}

/** Where a copy is. OpenPro's own downloads (Storage.localOriginal) aren't indexed: they're found by name. */
enum class CopyPlace {
    /** In the phone's or an SD card's gallery; [CopyEntity.location] is its MediaStore URI. */
    Gallery,
    /** In a folder the user picked; [CopyEntity.location] is its document URI. */
    Folder,
    /** On a server; [CopyEntity.location] is its path there, relative to the server's root. */
    Server,
}

/**
 * One copy OpenPro made of a clip: what it is ([clipId], [variant] and [stabilization]) apart from
 * where it is ([place], [location]). Saving, uploading and deleting keep this up to date, so what's
 * where doesn't depend on folder layouts, file names, or batch jobs being kept.
 */
@Entity(tableName = "copies", indices = [Index("clipId")])
data class CopyEntity(
    @PrimaryKey val id: String,
    /** [CameraMedia.id]: name, recording time and size on the camera. */
    val clipId: String,
    val variant: JobVariant,
    val stabilization: StabilizationChoice?,
    val place: CopyPlace,
    val location: String,
    /** The server, for [CopyPlace.Server]. */
    val serverId: String?,
    val fileName: String,
    /** Where it is, for people: "Phone gallery · OpenPro/HERO12 Black", "Folder "Backup"". */
    val placeLabel: String,
    val onRemovable: Boolean,
    val sizeBytes: Long,
    val sha1: String?,
    /** Serial number of the camera it came from, when known. */
    val cameraSerial: String?,
    val savedAt: Long,
) {
    val version: ClipVersion
        get() = when (variant) {
            JobVariant.Original -> ClipVersion.Original
            JobVariant.Preview -> ClipVersion.Preview
            JobVariant.Stabilized -> ClipVersion.Stabilized(stabilization ?: StabilizationChoice.Suggested)
        }

    val isLocal get() = place != CopyPlace.Server
}

@Dao
abstract class CopyDao {
    @Query("SELECT * FROM copies ORDER BY savedAt")
    abstract fun observe(): Flow<List<CopyEntity>>

    @Query("SELECT * FROM copies WHERE clipId = :clipId")
    abstract suspend fun forClip(clipId: String): List<CopyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(copy: CopyEntity)

    @Query("DELETE FROM copies WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Query("DELETE FROM copies WHERE place = 'Server' AND serverId = :serverId AND location = :path")
    abstract suspend fun deleteRemote(serverId: String, path: String)
}

@Database(entities = [CopyEntity::class, ClipEntity::class], version = 2, exportSchema = false)
abstract class CopyDb : RoomDatabase() {
    abstract fun dao(): CopyDao
    abstract fun clips(): ClipDao

    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS clips (id TEXT NOT NULL PRIMARY KEY, folder TEXT NOT NULL, name TEXT NOT NULL, " +
                        "sizeBytes INTEGER NOT NULL, createdSec INTEGER NOT NULL, modifiedSec INTEGER NOT NULL, lrvSizeBytes INTEGER NOT NULL, " +
                        "groupFirst INTEGER, groupLast INTEGER, cameraSerial TEXT, onCamera INTEGER NOT NULL, lastSeenAt INTEGER NOT NULL)",
                )
            }
        }

        fun open(context: Context): CopyDb = Room.databaseBuilder(context, CopyDb::class.java, "copies.db")
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}

/**
 * The copies index: every copy OpenPro has saved or uploaded, by clip. The whole index is kept in
 * memory ([byClip]) for the gallery's badges and quick checks; the batch waits for it to load
 * ([awaitLoaded]) before deciding anything from it.
 */
class CopyIndex(context: Context, private val storage: Storage, scope: CoroutineScope) {
    private val resolver = context.contentResolver
    /** Shared with the [ClipCatalogue], which keeps its table in the same database. */
    val db = CopyDb.open(context)
    private val dao = db.dao()
    private val _byClip = MutableStateFlow<Map<String, List<CopyEntity>>>(emptyMap())
    val byClip: StateFlow<Map<String, List<CopyEntity>>> = _byClip
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch(Dispatchers.IO) {
            dao.observe().collect { all ->
                _byClip.value = all.groupBy { it.clipId }
                loaded.complete(Unit)
            }
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    fun of(media: CameraMedia): List<CopyEntity> = _byClip.value[media.id].orEmpty()

    /** Copies of [version] of [media] on this phone (gallery or picked folder). */
    fun local(media: CameraMedia, version: ClipVersion): List<CopyEntity> = of(media).filter { it.isLocal && it.version == version }

    /** Copies of [version] of [media] on the server [serverId]. */
    fun remote(media: CameraMedia, version: ClipVersion, serverId: String): List<CopyEntity> =
        of(media).filter { it.place == CopyPlace.Server && it.serverId == serverId && it.version == version }

    /** Saved copies of [version] of [media] on the phone that are the size that version should be. */
    private fun whole(media: CameraMedia, version: ClipVersion): List<CopyEntity> {
        val expected = when (version) {
            ClipVersion.Original -> media.sizeBytes
            ClipVersion.Preview -> media.lrvSizeBytes
            is ClipVersion.Stabilized -> -1
        }
        return local(media, version).filter { expected <= 0 || it.sizeBytes == expected }
    }

    /**
     * A file on the phone holding [version] of [media] that OpenPro can read directly: its own
     * download (for originals), else a gallery copy of the expected size. Copies in a picked folder
     * aren't files OpenPro can open by path: see [openPhoneFile] and [phoneUri] for those.
     */
    fun phoneFile(media: CameraMedia, version: ClipVersion): File? {
        if (version == ClipVersion.Original) {
            storage.localOriginal(media)?.takeIf { media.sizeBytes <= 0 || it.length() == media.sizeBytes }?.let { return it }
        }
        return whole(media, version).filter { it.place == CopyPlace.Gallery }
            .firstNotNullOfOrNull { storage.galleryFile(android.net.Uri.parse(it.location), it.sizeBytes) }
    }

    /** A copy by its id, if it's still recorded. */
    fun byId(id: String): CopyEntity? = _byClip.value.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == id } }

    /** One particular copy on the phone as a file (see [openPhoneFile]), or null if it can't be opened. */
    fun openCopy(copy: CopyEntity): OpenFile? {
        if (copy.place == CopyPlace.Gallery) {
            storage.galleryFile(android.net.Uri.parse(copy.location), copy.sizeBytes)?.let { return OpenFile(it, it.length(), null) }
        }
        if (!copy.isLocal) return null
        val fd = runCatching { resolver.openFileDescriptor(android.net.Uri.parse(copy.location), "r") }.getOrNull() ?: return null
        return OpenFile(File("/proc/self/fd/${fd.fd}"), fd.statSize.takeIf { it >= 0 } ?: copy.sizeBytes, fd)
    }

    /** Whether the phone has [version] of [media], anywhere: uploads and stabilizing then needn't fetch it from the camera. */
    fun hasPhoneCopy(media: CameraMedia, version: ClipVersion): Boolean = phoneFile(media, version) != null || whole(media, version).isNotEmpty()

    /** A link to [version] of [media] on the phone, wherever it's saved: for reading it (thumbnails, lengths, photos). */
    fun phoneUri(media: CameraMedia, version: ClipVersion): android.net.Uri? =
        phoneFile(media, version)?.let(android.net.Uri::fromFile) ?: whole(media, version).firstOrNull()?.let { android.net.Uri.parse(it.location) }

    /**
     * [version] of [media] on the phone as a file, for code that reads files (uploads, copying for
     * the stabilizing engine): a gallery copy or OpenPro's own download as it is, and a copy in a
     * picked folder through a descriptor Android opens for it, readable until the result is closed.
     */
    fun openPhoneFile(media: CameraMedia, version: ClipVersion): OpenFile? {
        phoneFile(media, version)?.let { return OpenFile(it, it.length(), null) }
        for (copy in whole(media, version).filter { it.place == CopyPlace.Folder }) {
            val fd = runCatching { resolver.openFileDescriptor(android.net.Uri.parse(copy.location), "r") }.getOrNull() ?: continue
            return OpenFile(File("/proc/self/fd/${fd.fd}"), fd.statSize.takeIf { it >= 0 } ?: copy.sizeBytes, fd)
        }
        return null
    }

    suspend fun recordSaved(media: CameraMedia, version: ClipVersion, saved: Storage.Saved, cameraSerial: String?) = put(
        media, version, if (saved.inGallery) CopyPlace.Gallery else CopyPlace.Folder, saved.uri.toString(), null,
        saved.fileName, saved.label, saved.onRemovable, saved.sizeBytes, null, cameraSerial,
    )

    /** Records an upload; an earlier record of the same server path is replaced. */
    suspend fun recordUpload(
        media: CameraMedia, version: ClipVersion, serverId: String, serverName: String, path: String, size: Long, sha1: String?, cameraSerial: String?,
    ) {
        dao.deleteRemote(serverId, path)
        put(media, version, CopyPlace.Server, path, serverId, path.substringAfterLast('/'), serverName, false, size, sha1, cameraSerial)
    }

    suspend fun remove(copy: CopyEntity) = dao.delete(copy.id)

    suspend fun removeRemote(serverId: String, path: String) = dao.deleteRemote(serverId, path)

    /**
     * Drops records of copies on the phone that are gone (deleted in a gallery app, say). Copies on
     * a card or drive that isn't there are kept: they're unreachable, not gone. Server copies are
     * checked when they matter (before skipping an upload), not here.
     */
    suspend fun prune() {
        // One check at a time: returning to the app and opening the gallery can both ask at once.
        if (!pruning.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) {
                awaitLoaded()
                val local = _byClip.value.values.flatten().filter { it.isLocal }
                val (gallery, folders) = local.partition { it.place == CopyPlace.Gallery }
                val gone = storage.goneFromGallery(gallery.map { android.net.Uri.parse(it.location) }).map(android.net.Uri::toString).toSet()
                gallery.filter { it.location in gone }.forEach { dao.delete(it.id) }
                folders.filter { storage.localCopyGone(false, android.net.Uri.parse(it.location)) }.forEach { dao.delete(it.id) }
            }
        } finally {
            pruning.set(false)
        }
    }

    private val pruning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Drops records of [serverId]'s copies whose file is gone from the server, by listing each folder
     * they're in (one request per folder). Returns how many were checked and how many were gone.
     * Network errors are thrown: nothing is dropped on the strength of a failed request.
     */
    suspend fun checkServer(serverId: String, list: suspend (String) -> List<String>): Pair<Int, Int> = withContext(Dispatchers.IO) {
        awaitLoaded()
        val copies = _byClip.value.values.flatten().filter { it.place == CopyPlace.Server && it.serverId == serverId }
        var gone = 0
        for ((dir, inDir) in copies.groupBy { it.location.substringBeforeLast('/', "") }) {
            val names = list(dir).toSet()
            inDir.filter { it.location.substringAfterLast('/') !in names }.forEach { dao.delete(it.id); gone++ }
        }
        copies.size to gone
    }

    private suspend fun put(
        media: CameraMedia, version: ClipVersion, place: CopyPlace, location: String, serverId: String?, fileName: String,
        label: String, onRemovable: Boolean, size: Long, sha1: String?, cameraSerial: String?,
    ) = dao.put(
        CopyEntity(
            id = UUID.randomUUID().toString(),
            clipId = media.id,
            variant = when (version) {
                ClipVersion.Original -> JobVariant.Original
                ClipVersion.Preview -> JobVariant.Preview
                is ClipVersion.Stabilized -> JobVariant.Stabilized
            },
            stabilization = (version as? ClipVersion.Stabilized)?.method,
            place = place,
            location = location,
            serverId = serverId,
            fileName = fileName,
            placeLabel = label,
            onRemovable = onRemovable,
            sizeBytes = size,
            sha1 = sha1,
            cameraSerial = cameraSerial,
            savedAt = System.currentTimeMillis(),
        ),
    )
}
