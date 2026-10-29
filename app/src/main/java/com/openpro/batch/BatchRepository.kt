package com.openpro.batch

import android.content.Context
import com.openpro.media.CameraMedia
import com.openpro.quik.StabilizationChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** Live, in-memory progress of the job that is currently running (the DB holds the durable part). */
data class LiveProgress(
    val label: String,
    val done: Long,
    val total: Long,
    val bytesPerSecond: Long = 0,
    /** True when done/total are bytes; false for a 0..1000 render fraction. */
    val isBytes: Boolean = true,
    /** Going out over the internet (an upload), for the activity bar under the top bar. */
    val toInternet: Boolean = false,
)

/** Everything the UI does with batch jobs goes through here. */
class BatchRepository(private val context: Context, val db: BatchDb, val settings: BatchSettings) {
    private val dao = db.dao()
    private val _live = MutableStateFlow<Map<String, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<String, LiveProgress>> = _live

    /** Set by the runner so user cancellation can stop the job that is running right now. */
    @Volatile internal var cancelRunning: ((String) -> Unit)? = null

    /** Set by the runner: stops the running job so it resumes later (pausing to let the user have the Wi-Fi). */
    @Volatile internal var interruptRunning: (() -> Unit)? = null

    val jobs: Flow<List<JobEntity>> = dao.observeJobs()

    fun log(jobId: String): Flow<List<JobLogEntity>> = dao.observeLog(jobId)

    /**
     * Queues one job per media item. Items that already have an identical job queued, running or
     * waiting are skipped, so tapping twice never duplicates work. Returns how many were added.
     *
     * Photos are saved as they are when stabilized versions are asked for (there's nothing to
     * stabilize), anything without a preview (photos included) is skipped for preview jobs, and a
     * preview is never a reason to delete a clip from the camera.
     */
    suspend fun enqueue(
        media: List<CameraMedia>,
        action: JobAction,
        variant: JobVariant,
        stabilization: StabilizationChoice? = null,
        serverId: String? = null,
        options: UploadOptions = UploadOptions(),
        source: String? = null,
        placement: (CameraMedia) -> Placement = { Placement() },
    ): Int {
        var added = 0
        val now = System.currentTimeMillis()
        for ((i, m) in media.withIndex()) {
            val variant = if (!m.isVideo && variant == JobVariant.Stabilized) JobVariant.Original else variant
            if (variant == JobVariant.Preview && !m.hasLrv) continue
            // A kind meant for the other sort of clip (360 or flat), as a mixed selection can ask for,
            // becomes GoPro default for this one rather than failing.
            val method = if (variant == JobVariant.Stabilized) {
                (stabilization ?: StabilizationChoice.Suggested).takeIf { it.fits(m.extension == "360") } ?: StabilizationChoice.Suggested
            } else {
                null
            }
            if (dao.activeDuplicates(m.folder, m.name, action, variant, method, serverId) > 0) continue
            val where = placement(m)
            val job = JobEntity(
                id = UUID.randomUUID().toString(),
                folder = m.folder,
                name = m.name,
                sizeBytes = m.sizeBytes,
                lrvSizeBytes = m.lrvSizeBytes,
                createdSec = m.createdSec,
                modifiedSec = m.modifiedSec,
                action = action,
                variant = variant,
                stabilization = method,
                serverId = serverId,
                state = JobState.Queued,
                step = if (variant == JobVariant.Stabilized) Step.Render else Step.Fetch,
                bytesTotal = if (variant == JobVariant.Preview) m.lrvSizeBytes else m.sizeBytes,
                keepOnPhone = action == JobAction.Upload && options.keepOnPhone,
                deletePhoneCopy = action == JobAction.Upload && options.deletePhoneCopy && !options.keepOnPhone,
                deleteFromCamera = options.deleteFromCamera && variant != JobVariant.Preview,
                saveDir = where.saveDir,
                uploadDir = where.uploadDir,
                cameraSerial = where.cameraSerial,
                saveTarget = where.saveTarget,
                source = source,
                createdAt = now + i, // keeps selection order stable
                updatedAt = now,
            )
            dao.insert(job)
            dao.log(JobLogEntity(jobId = job.id, at = now, message = "Queued: ${job.title} ${m.name}"))
            added++
        }
        if (added > 0) BatchScheduler.kick(context)
        return added
    }

    /** A copy to delete in the batch: from the camera (no [serverId]) or from a server, at [path]. */
    data class DeleteRequest(
        val media: CameraMedia, val variant: JobVariant, val stabilization: StabilizationChoice?, val serverId: String?, val path: String?, val size: Long,
    )

    /**
     * Queues deletions that need a network: from the camera (its Wi-Fi) or from a server (the
     * internet). The batch runs those it can on the network the phone is on first. Returns how many
     * were added; ones already queued are skipped.
     */
    suspend fun enqueueDeletes(requests: List<DeleteRequest>): Int {
        var added = 0
        val now = System.currentTimeMillis()
        for ((i, r) in requests.withIndex()) {
            val m = r.media
            if (dao.activeDuplicates(m.folder, m.name, JobAction.Delete, r.variant, r.stabilization, r.serverId) > 0) continue
            val job = JobEntity(
                id = UUID.randomUUID().toString(),
                folder = m.folder,
                name = m.name,
                sizeBytes = m.sizeBytes,
                lrvSizeBytes = m.lrvSizeBytes,
                createdSec = m.createdSec,
                modifiedSec = m.modifiedSec,
                action = JobAction.Delete,
                variant = r.variant,
                stabilization = r.stabilization,
                serverId = r.serverId,
                state = JobState.Queued,
                step = if (r.serverId != null) Step.DeleteRemote else Step.DeleteFromCamera,
                remotePath = r.path,
                bytesTotal = r.size,
                // Lets the gallery drop the clip once it's gone from the camera, as for other jobs.
                deleteFromCamera = r.serverId == null,
                createdAt = now + i,
                updatedAt = now,
            )
            dao.insert(job)
            dao.log(JobLogEntity(jobId = job.id, at = now, message = "Queued: ${job.title} ${m.name}"))
            added++
        }
        if (added > 0) BatchScheduler.kick(context)
        return added
    }

    suspend fun retry(id: String) {
        val job = dao.get(id) ?: return
        if (job.state != JobState.Failed && job.state != JobState.Cancelled && job.state != JobState.Waiting) return
        save(job.copy(state = JobState.Queued, attempts = 0, error = null, nextRetryAt = 0, startedAt = 0, finishedAt = 0, runMs = 0), "Retry requested")
        BatchScheduler.kick(context)
    }

    suspend fun retryAllFailed() {
        dao.retryAllFailed(System.currentTimeMillis())
        BatchScheduler.kick(context)
    }

    suspend fun cancel(id: String) {
        val job = dao.get(id) ?: return
        if (!job.isActive) return
        if (job.state == JobState.Running) cancelRunning?.invoke(id)
        save(job.copy(state = JobState.Cancelled, waitingReason = null, finishedAt = System.currentTimeMillis()), "Cancelled by user")
    }

    suspend fun remove(id: String) {
        val job = dao.get(id) ?: return
        if (job.state == JobState.Running) cancel(id)
        dao.deleteWithLog(id)
    }

    suspend fun clearFinished() {
        for (id in dao.finishedIds()) dao.deleteWithLog(id)
    }

    /**
     * Pauses or resumes the queue. Pausing also stops the job running now, unless it's stabilizing
     * (which needs no network and can't resume part way): it goes back in the queue and carries on
     * from where it stopped once resumed.
     */
    fun setPaused(paused: Boolean) {
        settings.update { it.copy(paused = paused) }
        if (paused) interruptRunning?.invoke() else BatchScheduler.kick(context)
    }

    // --- used by the runner ---

    internal suspend fun get(id: String) = dao.get(id)

    internal suspend fun pending() = dao.pending()

    internal suspend fun resetInterrupted() = dao.resetRunning(System.currentTimeMillis())

    internal suspend fun save(job: JobEntity, logMessage: String? = null) {
        val now = System.currentTimeMillis()
        dao.update(job.copy(updatedAt = now))
        if (logMessage != null) dao.log(JobLogEntity(jobId = job.id, at = now, message = logMessage))
    }

    /** Runner-side save: only applies while the job is still Running. Returns false if it was cancelled. */
    internal suspend fun saveIfRunning(job: JobEntity, logMessage: String? = null): Boolean {
        val now = System.currentTimeMillis()
        val ok = dao.updateIfRunning(job.copy(updatedAt = now))
        if (ok && logMessage != null) dao.log(JobLogEntity(jobId = job.id, at = now, message = logMessage))
        return ok
    }

    internal suspend fun addRunTime(jobId: String, ms: Long) = dao.addRunTime(jobId, ms)

    internal suspend fun progress(jobId: String, done: Long, total: Long) =
        dao.progress(jobId, done, total, System.currentTimeMillis())

    internal suspend fun note(jobId: String, message: String) =
        dao.log(JobLogEntity(jobId = jobId, at = System.currentTimeMillis(), message = message))

    internal fun setLive(jobId: String, progress: LiveProgress?) =
        _live.update { if (progress == null) it - jobId else it + (jobId to progress) }
}
