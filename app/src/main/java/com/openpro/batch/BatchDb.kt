package com.openpro.batch

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import com.openpro.media.CameraMedia
import com.openpro.quik.StabilizationChoice
import kotlinx.coroutines.flow.Flow

/** Stored by name, so adding entries needs no migration. Delete jobs remove a copy from the camera or a server. */
enum class JobAction { Download, Upload, Delete }

/** Stored by name, so adding entries needs no migration. */
enum class JobVariant { Original, Stabilized, Preview }

enum class JobState { Queued, Running, Waiting, Failed, Done, Cancelled }

/** Steps run in this order; a job's [JobEntity.step] is the next step to run (its checkpoint). */
enum class Step { Fetch, Render, Upload, Verify, Publish, Cleanup, DeleteFromCamera, DeleteRemote, Finished }

/**
 * One batch job and its durable checkpoint. Every state change is a single-row update, so a job
 * interrupted at any point (app killed, reboot, camera asleep) resumes from [step].
 */
@Entity(tableName = "jobs", indices = [Index("state")])
data class JobEntity(
    @PrimaryKey val id: String,
    // What
    val folder: String,
    val name: String,
    val sizeBytes: Long,
    val lrvSizeBytes: Long,
    val createdSec: Long,
    val modifiedSec: Long,
    val action: JobAction,
    val variant: JobVariant,
    val stabilization: StabilizationChoice?,
    /** Upload destination; null for downloads to the phone. */
    val serverId: String?,
    // Where it is
    val state: JobState,
    val step: Step,
    val waitingReason: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
    val nextRetryAt: Long = 0,
    // Checkpoint
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val stagingPath: String? = null,
    val renderPath: String? = null,
    val remoteUploadId: String? = null,
    val remotePath: String? = null,
    /** SHA1 of the file being uploaded, for Nextcloud's integrity check. */
    val checksum: String? = null,
    /** Where the finished file ended up (gallery URI, file path or remote path). */
    val resultLocation: String? = null,
    // Options chosen when the job was queued
    /** Download & upload: also keep the clip on the phone, saved like a download. */
    val keepOnPhone: Boolean = false,
    /** Once the upload is verified, delete the clip's copy on the phone. */
    val deletePhoneCopy: Boolean = false,
    /** Once the clip is saved and checked, delete it from the camera. Cleared if that was skipped. */
    val deleteFromCamera: Boolean = false,
    /** Folders inside OpenPro's gallery folder this job saves into ("HERO12 Black"), null for none. Set when queued. */
    @androidx.room.ColumnInfo(name = "cameraFolder") val saveDir: String? = null,
    /** Folders inside the server's upload folder ("2026-08"), null for none. Set when queued. */
    val uploadDir: String? = null,
    /** Serial number of the camera the clip is from, for the copies index. */
    val cameraSerial: String? = null,
    /** Where it saves on the phone ([com.openpro.storage.SaveTarget.key]): Storage's setting when it was queued. */
    val saveTarget: String? = null,
    /**
     * Which copy of the clip to read, when the user chose one: "camera", or "copy:" and a copies
     * index id (on the phone or a server). Null: the best there is (see BatchRunner.sourceOf).
     */
    val source: String? = null,
    // Timing (0 = not yet)
    val startedAt: Long = 0,
    /** Time spent running so far, not counting time queued or waiting (its current run excluded). */
    val runMs: Long = 0,
    /** When its current run began, while [state] is Running. */
    val runStartedAt: Long = 0,
    val finishedAt: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val mediaId get() = toMedia().id

    fun toMedia() = CameraMedia(folder, name, sizeBytes, createdSec, modifiedSec, lrvSizeBytes, null, null)

    /** Which version of the clip this job saves. */
    val version: com.openpro.media.ClipVersion
        get() = when (variant) {
            JobVariant.Original -> com.openpro.media.ClipVersion.Original
            JobVariant.Preview -> com.openpro.media.ClipVersion.Preview
            JobVariant.Stabilized -> com.openpro.media.ClipVersion.Stabilized(stabilization ?: com.openpro.quik.StabilizationChoice.Suggested)
        }

    /** Size of the file this job fetches from the camera: the clip, or its preview. */
    val fetchBytes get() = if (variant == JobVariant.Preview) lrvSizeBytes else sizeBytes

    val isActive get() = state == JobState.Queued || state == JobState.Running || state == JobState.Waiting

    val title: String
        get() = buildString {
            append(
                when {
                    action == JobAction.Delete -> "Delete"
                    action == JobAction.Download -> "Download"
                    keepOnPhone -> "Download & upload"
                    else -> "Upload"
                },
            )
            when (variant) {
                JobVariant.Original -> {}
                JobVariant.Preview -> append(" preview")
                JobVariant.Stabilized -> append(" stabilized (${(stabilization ?: com.openpro.quik.StabilizationChoice.Suggested).shortLabel})")
            }
            if (action == JobAction.Delete) append(if (serverId != null) " from server" else " from camera")
        }
}

@Entity(tableName = "job_log", indices = [Index("jobId")])
data class JobLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: String,
    val at: Long,
    val message: String,
)

@Dao
abstract class BatchDao {
    @Query("SELECT * FROM jobs ORDER BY createdAt, name")
    abstract fun observeJobs(): Flow<List<JobEntity>>

    @Query("SELECT * FROM job_log WHERE jobId = :jobId ORDER BY at, id")
    abstract fun observeLog(jobId: String): Flow<List<JobLogEntity>>

    @Query("SELECT * FROM jobs WHERE id = :id")
    abstract suspend fun get(id: String): JobEntity?

    @Query("SELECT * FROM jobs WHERE state IN ('Queued', 'Waiting') ORDER BY createdAt, name")
    abstract suspend fun pending(): List<JobEntity>

    @Query(
        "SELECT COUNT(*) FROM jobs WHERE folder = :folder AND name = :name AND action = :action " +
            "AND variant = :variant AND IFNULL(stabilization, '') = IFNULL(:stabilization, '') " +
            "AND IFNULL(serverId, '') = IFNULL(:serverId, '') AND state IN ('Queued', 'Running', 'Waiting')",
    )
    abstract suspend fun activeDuplicates(
        folder: String, name: String, action: JobAction, variant: JobVariant, stabilization: StabilizationChoice?, serverId: String?,
    ): Int

    @Insert
    abstract suspend fun insert(job: JobEntity)

    @Update
    abstract suspend fun update(job: JobEntity)

    @Insert
    abstract suspend fun log(entry: JobLogEntity)

    @Query("UPDATE jobs SET state = 'Queued', updatedAt = :now WHERE state = 'Running'")
    abstract suspend fun resetRunning(now: Long)

    @Query(
        "UPDATE jobs SET state = 'Queued', attempts = 0, error = NULL, nextRetryAt = 0, startedAt = 0, finishedAt = 0, runMs = 0, " +
            "updatedAt = :now WHERE state = 'Failed'",
    )
    abstract suspend fun retryAllFailed(now: Long)

    @Query("DELETE FROM jobs WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Query("DELETE FROM job_log WHERE jobId = :jobId")
    abstract suspend fun deleteLog(jobId: String)

    @Query("SELECT id FROM jobs WHERE state IN ('Done', 'Cancelled')")
    abstract suspend fun finishedIds(): List<String>

    @Transaction
    open suspend fun deleteWithLog(id: String) {
        delete(id)
        deleteLog(id)
    }

    /** Adds a finished run's length to the job's running time. */
    @Query("UPDATE jobs SET runMs = runMs + :ms WHERE id = :id")
    abstract suspend fun addRunTime(id: String, ms: Long)

    @Query("UPDATE jobs SET bytesDone = :done, bytesTotal = :total, updatedAt = :now WHERE id = :id AND state = 'Running'")
    abstract suspend fun progress(id: String, done: Long, total: Long, now: Long): Int

    /** Writes [job] only if it is still Running, so a user's cancel is never overwritten by the runner. */
    @Transaction
    open suspend fun updateIfRunning(job: JobEntity): Boolean {
        if (get(job.id)?.state != JobState.Running) return false
        update(job)
        return true
    }
}

@Database(entities = [JobEntity::class, JobLogEntity::class], version = 9, exportSchema = false)
abstract class BatchDb : RoomDatabase() {
    abstract fun dao(): BatchDao

    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN checksum TEXT")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf("keepOnPhone", "deletePhoneCopy", "deleteFromCamera", "startedAt", "finishedAt").forEach {
                    db.execSQL("ALTER TABLE jobs ADD COLUMN $it INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN cameraFolder TEXT")
            }
        }

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN uploadDir TEXT")
                db.execSQL("ALTER TABLE jobs ADD COLUMN cameraSerial TEXT")
            }
        }

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN savedSince INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN runMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE jobs ADD COLUMN runStartedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Drops savedSince (deleting from the camera after uploading to several servers, which went
         * when uploads went back to one server) and adds saveTarget. SQLite on older Android can't
         * drop a column, so the table is rebuilt as Room defines it.
         */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `jobs_new` (`id` TEXT NOT NULL, `folder` TEXT NOT NULL, `name` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                        "`lrvSizeBytes` INTEGER NOT NULL, `createdSec` INTEGER NOT NULL, `modifiedSec` INTEGER NOT NULL, `action` TEXT NOT NULL, " +
                        "`variant` TEXT NOT NULL, `stabilization` TEXT, `serverId` TEXT, `state` TEXT NOT NULL, `step` TEXT NOT NULL, " +
                        "`waitingReason` TEXT, `error` TEXT, `attempts` INTEGER NOT NULL, `nextRetryAt` INTEGER NOT NULL, `bytesDone` INTEGER NOT NULL, " +
                        "`bytesTotal` INTEGER NOT NULL, `stagingPath` TEXT, `renderPath` TEXT, `remoteUploadId` TEXT, `remotePath` TEXT, " +
                        "`checksum` TEXT, `resultLocation` TEXT, `keepOnPhone` INTEGER NOT NULL, `deletePhoneCopy` INTEGER NOT NULL, " +
                        "`deleteFromCamera` INTEGER NOT NULL, `cameraFolder` TEXT, `uploadDir` TEXT, `cameraSerial` TEXT, `saveTarget` TEXT, " +
                        "`startedAt` INTEGER NOT NULL, `runMs` INTEGER NOT NULL, `runStartedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                val columns = "id, folder, name, sizeBytes, lrvSizeBytes, createdSec, modifiedSec, action, variant, stabilization, serverId, state, step, waitingReason, error, attempts, nextRetryAt, bytesDone, bytesTotal, stagingPath, renderPath, remoteUploadId, remotePath, checksum, resultLocation, keepOnPhone, deletePhoneCopy, deleteFromCamera, cameraFolder, uploadDir, cameraSerial, startedAt, runMs, runStartedAt, finishedAt, createdAt, updatedAt"
                db.execSQL("INSERT INTO jobs_new ($columns) SELECT $columns FROM jobs")
                db.execSQL("DROP TABLE jobs")
                db.execSQL("ALTER TABLE jobs_new RENAME TO jobs")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_jobs_state` ON `jobs` (`state`)")
            }
        }

        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN source TEXT")
            }
        }

        fun open(context: Context): BatchDb = Room.databaseBuilder(context, BatchDb::class.java, "batch.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .build()
    }
}
