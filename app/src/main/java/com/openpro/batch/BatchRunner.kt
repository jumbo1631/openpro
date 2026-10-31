package com.openpro.batch

import android.util.Log
import com.openpro.OpenProApp
import com.openpro.camera.CameraState
import com.openpro.camera.http.CameraApi
import com.openpro.camera.http.CameraStreams
import com.openpro.media.CameraMedia
import com.openpro.media.ClipNames
import com.openpro.media.ClipVersion
import com.openpro.net.Internet
import com.openpro.quik.CameraSource
import com.openpro.quik.JobKind
import com.openpro.quik.JobSource
import com.openpro.quik.QuikJob
import com.openpro.quik.StabilizationChoice
import com.openpro.ui.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Executes batch jobs one at a time (the camera's web server and GoPro's engine both cope badly
 * with parallel work). Each job runs its remaining steps; after every step the checkpoint is saved,
 * so an interruption anywhere resumes at that step. A job whose next step needs a network the phone
 * isn't on (camera or internet) goes back to the queue, where [blocker] switches networks or waits.
 * Unmet preconditions put a job in Waiting with a reason instead of failing it; errors are
 * classified into retry-with-backoff or fail.
 */
class BatchRunner(private val app: OpenProApp) {
    private val repo get() = app.batch
    private val config get() = repo.settings.config.value
    private var resetDone = false
    @Volatile private var running: Pair<String, Deferred<JobEntity>>? = null
    @Volatile private var runningRenderId: String? = null

    /** Result of one pass: either a job ran, or nothing is runnable until [wakeAt] (null = until a trigger). */
    sealed interface Pass {
        data object Ran : Pass
        data class Idle(val wakeAt: Long?, val waiting: Int) : Pass
    }

    suspend fun runOnePass(): Pass {
        if (!resetDone) {
            // A job marked Running here was interrupted by process death; resume it.
            repo.resetInterrupted()
            resetDone = true
        }
        repo.cancelRunning = { id -> cancel(id) }
        repo.interruptRunning = { interrupt() }
        if (config.paused) return Pass.Idle(null, 0)

        // Several decisions below (skip, reuse a phone copy) read the copies index.
        app.copies.awaitLoaded()
        val now = System.currentTimeMillis()
        val pending = repo.pending()
        val due = pending.filter { it.nextRetryAt <= now }
        internetThisPass.clear()
        var triedJoin = false
        var waiting = 0
        // Per item: the upload job holding a staged/rendered file; others don't stage until it's done.
        var holder: JobEntity? = null
        // Clips with downloads or uploads still to do: deleting them from the camera waits for those.
        val transferring = pending.filter { it.action != JobAction.Delete }.map { it.mediaId }.toSet()
        for (job in order(due)) {
            val blocker = try {
                deleteBlocker(job, transferring) ?: perItemBlocker(job, holder) ?: blocker(job, joinAllowed = !triedJoin)
            } catch (e: Exception) {
                e.message ?: "Waiting"
            } finally {
                if (needsCamera(job)) triedJoin = true
            }
            if (holder == null && config.uploadStrategy == UploadStrategy.PerItem && holdsLocalCopy(job)) holder = job
            if (blocker != null) {
                waiting++
                if (job.state != JobState.Waiting || job.waitingReason != blocker) {
                    repo.save(job.copy(state = JobState.Waiting, waitingReason = blocker), "Waiting: $blocker")
                }
                continue
            }
            execute(job)
            return Pass.Ran
        }
        val nextRetry = (pending.filter { it.nextRetryAt > now }.map { it.nextRetryAt } + listOfNotNull(internetRetryAt.takeIf { it > now }))
            .minOrNull()
        return Pass.Idle(nextRetry, waiting + pending.count { it.nextRetryAt > now })
    }

    /**
     * The order jobs are tried in. Per item: anything already holding a local copy first (finish
     * that item), then queue order. As a batch: everything that needs the camera first, so the
     * phone switches to the internet once, after the last download.
     *
     * Delete jobs are quick, so those that can run on the network the phone is on now go first and
     * the rest last: deleting from the camera and a server switches Wi-Fi once, not back and forth.
     */
    private fun order(jobs: List<JobEntity>): List<JobEntity> {
        val (deletes, transfers) = jobs.partition { it.action == JobAction.Delete }
        val ordered = when (config.uploadStrategy) {
            UploadStrategy.PerItem -> transfers.sortedWith(compareBy<JobEntity> { if (holdsLocalCopy(it)) 0 else 1 }.thenBy { it.createdAt })
            // Deleting from the camera comes last, after every upload, so the phone switches back once.
            UploadStrategy.Batch -> transfers.sortedWith(
                compareBy<JobEntity> { if (it.step == Step.DeleteFromCamera) 2 else if (needsCamera(it)) 0 else 1 }.thenBy { it.createdAt },
            )
        }
        val onCamera = app.camera.state.value is CameraState.Online
        val (now, later) = deletes.sortedBy { it.createdAt }.partition { needsCamera(it) == onCamera }
        return now + ordered + later
    }

    /** A deletion from the camera waits while the same clip still has downloads or uploads to do. */
    private fun deleteBlocker(job: JobEntity, transferring: Set<String>): String? =
        if (job.action == JobAction.Delete && job.step == Step.DeleteFromCamera && job.mediaId in transferring) {
            "Waiting for this clip's downloads and uploads to finish before deleting it from the camera"
        } else {
            null
        }

    /** An upload job past its download/render step: its file is on the phone waiting to go out. */
    private fun holdsLocalCopy(job: JobEntity) =
        job.action == JobAction.Upload && job.step in setOf(Step.Upload, Step.Verify, Step.Publish, Step.Cleanup, Step.DeleteFromCamera)

    private fun perItemBlocker(job: JobEntity, holder: JobEntity?): String? {
        if (config.uploadStrategy != UploadStrategy.PerItem || holder == null || holder.id == job.id) return null
        if (job.action != JobAction.Upload || holdsLocalCopy(job)) return null
        return "Next in line: waiting for ${holder.name} to finish uploading (upload per item)"
    }

    /** Where a job reads its clip from: a copy on the phone, the camera, or a copy on a server. */
    internal sealed interface ClipSource {
        /** A copy on the phone: [copy] if the user chose one, else whichever is there. */
        data class Phone(val copy: com.openpro.copies.CopyEntity?) : ClipSource
        data object Camera : ClipSource
        data class Server(val copy: com.openpro.copies.CopyEntity) : ClipSource
    }

    /**
     * The copy [job] reads (the original, or for preview jobs the preview): the one the user chose,
     * while it's still recorded; otherwise a copy on the phone, then the camera (if the clip is still
     * on it), then a copy on a server (not the one an upload is going to).
     */
    internal fun sourceOf(job: JobEntity): ClipSource {
        val media = job.toMedia()
        val version = if (job.variant == JobVariant.Preview) ClipVersion.Preview else ClipVersion.Original
        when (val key = job.source) {
            null -> {}
            "camera" -> return ClipSource.Camera
            else -> app.copies.byId(key.removePrefix("copy:"))?.let { c -> return if (c.isLocal) ClipSource.Phone(c) else ClipSource.Server(c) }
        }
        if (app.copies.hasPhoneCopy(media, version)) return ClipSource.Phone(null)
        if (app.catalogue[media.id]?.onCamera != false) return ClipSource.Camera
        app.copies.of(media).firstOrNull {
            it.place == com.openpro.copies.CopyPlace.Server && it.version == version && it.serverId != job.serverId && app.servers.get(it.serverId) != null
        }?.let { return ClipSource.Server(it) }
        return ClipSource.Camera
    }

    /** True when the job's next step reads from the camera. */
    fun needsCamera(job: JobEntity): Boolean = when (job.step) {
        Step.Fetch, Step.Render -> sourceOf(job) == ClipSource.Camera
        Step.DeleteFromCamera -> true
        else -> false
    }

    /** True when the job's next step talks to a server: uploading, deleting there, or fetching the clip from there. */
    fun needsInternet(job: JobEntity): Boolean =
        (job.action == JobAction.Upload && job.step in setOf(Step.Upload, Step.Verify)) || job.step == Step.DeleteRemote ||
            (job.step == Step.Fetch || job.step == Step.Render) && sourceOf(job) is ClipSource.Server

    /** Why the job can't run right now, or null if it can. May switch the phone between networks. */
    private suspend fun blocker(job: JobEntity, joinAllowed: Boolean): String? {
        val camera = needsCamera(job)
        val internet = needsInternet(job)
        if (camera) {
            CameraStreams.holder.value?.takeIf { it.who != CameraStreams.Who.Batch }?.let { return cameraBusy() }
            ensureCamera(job, joinAllowed)?.let { return it }
        }
        storageBlocker(job)?.let { return it }
        spaceNeeded(job)?.let { need ->
            val free = app.storage.freeBytes()
            val where = app.storage.workVolume().shortLabel
            if (free < need) return "Not enough space on $where: needs ${formatBytes(need)} free, ${formatBytes(free)} available"
        }
        if (internet && !camera) ensureInternet(job)?.let { return it }
        return Uploads.blocker(app, job, config)
    }

    private suspend fun ensureCamera(job: JobEntity, joinAllowed: Boolean): String? {
        // Queued for another camera's clip (queued while browsing without it): wait for that one.
        val connected = app.camera.current
        if (job.cameraSerial != null && connected?.serial != null && connected.serial != job.cameraSerial) {
            val name = app.camera.saved.bySerial(job.cameraSerial)?.name ?: "the camera it's on"
            return "Waiting for $name (${connected.name} is connected)"
        }
        when (app.camera.state.value) {
            is CameraState.Online -> return null
            is CameraState.Ready -> {
                if (!config.switchWifi) return "Waiting for the camera's Wi-Fi: connect to it from the gallery (Wi-Fi switching is off in batch settings)"
                if (!joinAllowed) return "Waiting for the camera's Wi-Fi"
                return switching(job, "Switching Wi-Fi to camera…") {
                    try {
                        withContext(Dispatchers.Main) { app.camera.goOnline() }
                        repo.note(job.id, "On the camera's Wi-Fi (${app.camera.credentials.value?.ssid ?: "camera"})")
                        null
                    } catch (e: Exception) {
                        "Couldn't join the camera's Wi-Fi: ${e.message}"
                    }
                }
            }
            else -> return "Waiting for the camera: open OpenPro and connect to it"
        }
    }

    /** Next time it's worth leaving the camera to look for internet again (after a failed look). */
    @Volatile private var internetRetryAt = 0L
    private var internetMisses = 0

    /**
     * The internet verdict already reached in this pass, per server (so 50 waiting uploads wait once,
     * not 50 times). Only "no" verdicts are kept: a "yes" can go stale as soon as a job switches Wi-Fi.
     */
    private val internetThisPass = mutableMapOf<String?, String>()

    /**
     * Set when a server couldn't be reached while the phone was on the camera's Wi-Fi, though Android
     * reported internet there (mobile data or a VPN that doesn't reach it). Until the phone leaves
     * the camera, uploads wait for the internet instead of each timing out first.
     */
    @Volatile private var noInternetOnCamera = false

    /**
     * Internet uploads can use right now: any, within the mobile data setting. Whether a server is
     * reachable over it (a VPN, the right Wi-Fi) is the user's to arrange; the upload finds out.
     */
    private fun internetNow(net: Internet = app.net.internet.value): Boolean {
        val onCamera = app.camera.state.value is CameraState.Online
        if (!onCamera) noInternetOnCamera = false
        return net.usable(config.uploadsOverMobileData) && !(onCamera && noInternetOnCamera)
    }

    /** Why a job that finished a step waits for its next one, which needs another network. */
    private fun nextNetworkReason(job: JobEntity): String =
        if (needsInternet(job)) "Waiting for internet" else "Waiting for the camera"

    private suspend fun ensureInternet(job: JobEntity): String? {
        if (internetNow()) return null
        internetThisPass[job.serverId]?.let { return it }
        return checkInternet(job)?.also { internetThisPass[job.serverId] = it }
    }

    private suspend fun checkInternet(job: JobEntity): String? {
        if (app.camera.state.value !is CameraState.Online) {
            // Not on the camera; give a fresh network a moment (e.g. the VPN reconnecting).
            if (app.net.awaitInternet(10_000) { internetNow(it) }) return null
            return noInternetReason(job)
        }
        // On the camera's Wi-Fi without usable internet: leave it so the phone rejoins its own Wi-Fi,
        // unless the user turned that off (they'd rather use mobile data alongside the camera's Wi-Fi).
        if (!config.switchWifi) {
            return "Waiting for internet. Wi-Fi switching is off (batch settings), so uploads need mobile data while the " +
                "phone is on the camera's Wi-Fi" + if (config.uploadsOverMobileData) "." else ", and uploads over mobile data are off."
        }
        if (app.camera.uiHolds.value > 0) {
            return "Ready, but waiting to switch Wi-Fi to the internet until you leave the camera screens"
        }
        val now = System.currentTimeMillis()
        if (now < internetRetryAt) {
            return noInternetReason(job) + " Looking again at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(internetRetryAt)}."
        }
        return switching(job, "Switching Wi-Fi to internet…") {
            withContext(Dispatchers.Main) { app.camera.parkWifi() }
            repo.note(job.id, "Left the camera's Wi-Fi; waiting for the phone's internet")
            if (app.net.awaitInternet(INTERNET_WAIT_MS) { internetNow(it) }) {
                internetMisses = 0
                internetRetryAt = 0
                repo.note(job.id, "On the internet (${app.net.internet.value.label})")
                null
            } else {
                internetMisses++
                internetRetryAt = System.currentTimeMillis() + BatchErrors.backoffMs(internetMisses + 2)
                noInternetReason(job)
            }
        }
    }

    private fun noInternetReason(job: JobEntity): String {
        val net = app.net.internet.value
        val allowMobile = config.uploadsOverMobileData
        return when {
            net.connected && net.mobile && !net.wifi && !allowMobile ->
                "Only mobile data is available, and uploads over mobile data are off (batch settings)."
            else -> "No internet for uploads. They continue when the phone is back on Wi-Fi" +
                (if (allowMobile) " or mobile data." else ".")
        }
    }

    /** Runs a network switch with the job and every screen showing [label] while it happens. */
    private suspend fun <T> switching(job: JobEntity, label: String, block: suspend () -> T): T {
        app.net.setSwitching(label)
        repo.setLive(job.id, LiveProgress(label, 0, 0))
        repo.note(job.id, label)
        try {
            return block()
        } finally {
            app.net.setSwitching(null)
            repo.setLive(job.id, null)
        }
    }

    /**
     * Storage the job's next step needs that isn't there (SD card removed, picked folder
     * unplugged, target full). Checked before downloading too, so gigabytes aren't fetched only
     * to have nowhere to go.
     */
    private fun storageBlocker(job: JobEntity): String? {
        val storage = app.storage
        val writesWork = job.step == Step.Fetch || job.step == Step.Render
        if (writesWork) runCatching { storage.workVolume() }.onFailure { return it.message }
        val publishes = job.action == JobAction.Download && job.step != Step.Finished
        val target = storage.targetOf(job.saveTarget)
        if (publishes) runCatching { storage.checkSaveTarget(target) }.onFailure { return it.message }
        if (job.step == Step.Publish) {
            val file = job.renderPath?.let(::File) ?: storage.localOriginal(job.toMedia())
            if (file == null) {
                storage.missingOriginal(job.toMedia())?.let { return "The downloaded file is on $it, which isn't inserted" }
            }
            val free = storage.saveTargetFreeBytes(target)
            val need = (file?.length() ?: job.sizeBytes) + 50L * 1024 * 1024
            if (free != null && free < need) {
                return "Not enough space in ${storage.saveTargetLabel(target)}: needs ${formatBytes(need)}, ${formatBytes(free)} free"
            }
        }
        return null
    }

    private fun spaceNeeded(job: JobEntity): Long? {
        val margin = { bytes: Long -> bytes + bytes / 10 + 50L * 1024 * 1024 }
        return when {
            job.step == Step.Fetch -> margin(job.fetchBytes - partialBytes(job))
            // A stabilized render is at most about the size of the original; a clip in a picked
            // folder or on a server is copied to working storage first, which needs as much again.
            job.step == Step.Render -> margin(job.sizeBytes * if (needsEngineCopy(job)) 2 else 1)
            else -> null
        }
    }

    /** Stabilizing [job] first puts a copy of the clip in working storage (from the camera, a picked folder or a server). */
    private fun needsEngineCopy(job: JobEntity): Boolean = when (val src = sourceOf(job)) {
        ClipSource.Camera, is ClipSource.Server -> true
        is ClipSource.Phone -> src.copy?.place == com.openpro.copies.CopyPlace.Folder ||
            src.copy == null && app.copies.phoneFile(job.toMedia(), ClipVersion.Original) == null
    }

    private fun partialBytes(job: JobEntity): Long {
        val target = when {
            job.variant == JobVariant.Preview -> app.storage.previewStagingFile(job.toMedia())
            job.action == JobAction.Download || job.keepOnPhone -> app.storage.originalFile(job.toMedia())
            else -> app.storage.stagingFile(job.toMedia())
        }
        return File(target.path + ".part").length()
    }

    private suspend fun execute(start: JobEntity) {
        val runStart = System.currentTimeMillis()
        var job = start.copy(
            state = JobState.Running, waitingReason = null, startedAt = start.startedAt.takeIf { it > 0 } ?: runStart, runStartedAt = runStart,
        )
        repo.save(job, "Running (step ${job.step.name.lowercase()}, attempt ${job.attempts + 1})")
        try {
            val finished = coroutineScope {
                val work = async(start = CoroutineStart.LAZY) { runSteps(job) }
                running = job.id to work
                work.await()
            }
            if (finished.step != Step.Finished) {
                // The next step needs another network; the scheduler switches or waits for it.
                val reason = nextNetworkReason(finished)
                repo.saveIfRunning(finished.copy(state = JobState.Waiting, waitingReason = reason), "Next: ${finished.step.name.lowercase()}. $reason")
            } else if (repo.saveIfRunning(finished.copy(state = JobState.Done, error = null, finishedAt = System.currentTimeMillis()), "Done: ${finished.resultLocation ?: ""}")) {
                Log.i(TAG, "job ${job.id} done")
            }
        } catch (e: CancellationException) {
            val now = repo.get(job.id)
            if (now?.state == JobState.Cancelled) {
                cleanupAfterCancel(now)
            } else if (pausing) {
                // The user paused the queue (e.g. to browse the camera): back in line, nothing lost.
                pausing = false
                now?.let { repo.save(it.copy(state = JobState.Queued), "Paused; will resume") }
            } else {
                // The worker is being stopped (system limits, app update): pick up here next time.
                now?.let { repo.save(it.copy(state = JobState.Queued), "Interrupted; will resume") }
                throw e
            }
        } catch (t: Throwable) {
            job = repo.get(job.id) ?: return
            if (job.state != JobState.Running) return
            Log.w(TAG, "job ${job.id} step ${job.step} failed", t)
            val message = BatchErrors.describe(t)
            when (BatchErrors.classify(t)) {
                Outcome.Wait -> repo.save(job.copy(state = JobState.Waiting, waitingReason = message), "Waiting: $message")
                Outcome.Fail -> repo.save(job.copy(state = JobState.Failed, error = message, finishedAt = System.currentTimeMillis()), "Failed: $message")
                // A server out of reach from the camera's Wi-Fi: the internet Android reported there
                // isn't real for it. Wait for the phone to leave the camera, without using an attempt.
                Outcome.Retry if needsInternet(job) && app.camera.state.value is CameraState.Online && BatchErrors.isUnreachable(t) -> {
                    noInternetOnCamera = true
                    val reason = nextNetworkReason(job)
                    repo.save(job.copy(state = JobState.Waiting, waitingReason = reason), "Couldn't reach the server over the camera's Wi-Fi ($message). $reason")
                }
                Outcome.Retry -> lostConnection(job)?.let { reason ->
                    repo.save(job.copy(state = JobState.Waiting, waitingReason = reason), "Interrupted: $message. $reason")
                } ?: run {
                    val attempts = job.attempts + 1
                    if (attempts >= BatchErrors.MAX_ATTEMPTS) {
                        repo.save(
                            job.copy(state = JobState.Failed, attempts = attempts, error = message, finishedAt = System.currentTimeMillis()),
                            "Failed after $attempts attempts: $message",
                        )
                    } else {
                        val delay = BatchErrors.backoffMs(attempts)
                        repo.save(
                            job.copy(state = JobState.Queued, attempts = attempts, error = message, nextRetryAt = System.currentTimeMillis() + delay),
                            "Attempt $attempts failed: $message. Retrying in ${delay / 1000} s",
                        )
                    }
                }
            }
        } finally {
            running = null
            repo.setLive(job.id, null)
            // Only time spent running counts: the clock stops while it's queued or waiting.
            withContext(NonCancellable) { repo.addRunTime(job.id, System.currentTimeMillis() - runStart) }
        }
    }

    /**
     * If a step failed because the camera's Wi-Fi or the internet went away, the job waits for it
     * to come back instead of using up an attempt.
     */
    private fun lostConnection(job: JobEntity): String? = when {
        needsCamera(job) && app.camera.state.value !is CameraState.Online -> "Lost the camera's Wi-Fi; resumes when it's back"
        needsInternet(job) && !internetNow() -> "Lost the internet connection; resumes when it's back"
        else -> null
    }

    /**
     * Runs the job's steps until it finishes, or until the next step needs a network the phone
     * isn't on right now (e.g. the upload after a download on the camera's Wi-Fi). Then the job is
     * returned unfinished and goes back to the queue, where [blocker] switches networks or waits:
     * a step must never run over whatever network happens to be there (such as mobile data with
     * uploads over mobile data off).
     */
    private suspend fun runSteps(initial: JobEntity): JobEntity {
        var job = initial
        while (job.step != Step.Finished) {
            if (job !== initial && !readyNow(job)) return job
            val next = when (job.action) {
                JobAction.Download -> downloadStep(job)
                JobAction.Upload -> Uploads.step(app, this, job, config)
                JobAction.Delete -> deleteStep(job)
            }
            if (!repo.saveIfRunning(next, "Step ${job.step.name.lowercase()} complete")) throw CancellationException("cancelled")
            job = next
        }
        return job
    }

    /** True if the job's next step can run on the networks the phone is on right now. */
    private fun readyNow(job: JobEntity): Boolean =
        (!needsCamera(job) || app.camera.state.value is CameraState.Online) && (!needsInternet(job) || internetNow())

    // --- delete jobs ---

    /** Deletes the job's copy: the clip on the camera, or a file on a server (and its index record). */
    private suspend fun deleteStep(job: JobEntity): JobEntity = when (job.step) {
        Step.DeleteFromCamera -> Uploads.deleteFromCamera(app, this, job).copy(resultLocation = "Deleted from the camera")
        Step.DeleteRemote -> {
            val server = app.servers.get(job.serverId) ?: throw PermanentException("The server was removed under Servers")
            val path = job.remotePath ?: throw PermanentException("No path on the server was recorded")
            app.servers.client(server).delete(path)
            app.copies.removeRemote(server.id, path)
            repo.note(job.id, "Deleted $path from ${server.name}")
            job.copy(step = Step.Finished, resultLocation = "Deleted from ${server.name}: $path")
        }
        else -> job.copy(step = Step.Finished)
    }

    // --- download jobs ---

    private suspend fun downloadStep(job: JobEntity): JobEntity {
        val media = job.toMedia()
        val skip = config.conflictPolicy == ConflictPolicy.SkipIdentical
        // The phone's copy is saved (and its size checked): on to deleting it from the camera, if asked.
        fun saved(j: JobEntity) = j.copy(step = if (j.deleteFromCamera) Step.DeleteFromCamera else Step.Finished)
        return when (job.step) {
            Step.Fetch -> {
                if (job.variant == JobVariant.Preview) return fetchPreview(job, media, skip)
                if (skip) savedAlready(job, media)?.let { return saved(it) }
                if (!skip) app.storage.originalFile(media).delete()
                // Already downloaded earlier (possibly to another volume): use that copy.
                val kept = app.storage.localOriginal(media)?.takeIf { skip && (media.sizeBytes <= 0 || it.length() == media.sizeBytes) }
                if (kept != null) repo.note(job.id, "Using the copy already on ${app.storage.volumes.volumeOf(kept)?.shortLabel ?: "the phone"}")
                val file = kept ?: fetch(job, media, app.storage.originalFile(media))
                val fetched = job.copy(resultLocation = file.path)
                fetched.copy(step = Step.Publish)
            }
            Step.Render -> {
                if (skip) {
                    savedAlready(job, media)?.let { done ->
                        // Nothing new was saved from the clip, so it isn't a reason to delete it.
                        if (job.deleteFromCamera) repo.note(job.id, "Kept the clip on the camera: nothing new was saved from it")
                        return done.copy(step = Step.Finished, deleteFromCamera = false)
                    }
                }
                val out = render(job, media)
                job.copy(step = Step.Publish, renderPath = out.path)
            }
            Step.Publish -> {
                // The original may be on another volume than today's work volume; look everywhere.
                val file = job.renderPath?.let(::File)?.takeIf { it.exists() } ?: app.storage.localOriginal(media)
                if (file == null) {
                    app.storage.missingOriginal(media)?.let { throw com.openpro.storage.StorageUnavailableException("The downloaded file is on $it, which isn't inserted") }
                    throw PermanentException("The finished file disappeared before it could be saved; retry to redo it")
                }
                val saved = publish(job, media, file)
                if (job.renderPath == null) app.storage.forgetOriginal(media)
                saved(job.copy(resultLocation = "${saved.label}: ${saved.fileName}", renderPath = null))
            }
            Step.DeleteFromCamera -> Uploads.deleteFromCamera(app, this, job)
            else -> job.copy(step = Step.Finished)
        }
    }

    /**
     * A preview download: the camera's low-res proxy, fetched to the staging folder and then saved
     * like a stabilized render (always where finished videos go: there's no "kept by OpenPro" copy
     * of a preview). It's treated as the prepared file ([JobEntity.renderPath]) from here on.
     */
    private suspend fun fetchPreview(job: JobEntity, media: CameraMedia, skip: Boolean): JobEntity {
        if (!media.hasLrv) throw PermanentException("The camera has no preview for this clip")
        if (skip) savedAlready(job, media)?.let { return it.copy(step = Step.Finished, deleteFromCamera = false) }
        val file = fetch(job, media, app.storage.previewStagingFile(media))
        return job.copy(step = Step.Publish, renderPath = file.path)
    }

    /**
     * A copy of the job's version already on the phone, by the copies index (checked to still be
     * there, and for originals and previews the same size as the camera's): the job with a note,
     * or null to go ahead.
     */
    private suspend fun savedAlready(job: JobEntity, media: CameraMedia): JobEntity? {
        val expected = when (job.variant) {
            JobVariant.Original -> media.sizeBytes
            JobVariant.Preview -> media.lrvSizeBytes
            JobVariant.Stabilized -> -1
        }
        val copy = app.copies.local(media, job.version).firstOrNull { c ->
            (expected <= 0 || c.sizeBytes == expected) && !app.storage.localCopyGone(c.place == com.openpro.copies.CopyPlace.Gallery, android.net.Uri.parse(c.location))
        } ?: return null
        repo.note(job.id, "Already saved: ${copy.placeLabel}: ${copy.fileName}; skipped")
        return job.copy(resultLocation = "Already downloaded: ${copy.placeLabel}: ${copy.fileName}")
    }

    /**
     * Saves the job's finished [file] where finished files go, in the job's folders, under its
     * version's name, and records it in the copies index.
     */
    internal suspend fun publish(job: JobEntity, media: CameraMedia, file: File): com.openpro.storage.Storage.Saved {
        val name = savedName(media, job.version)
        val overwrite = config.conflictPolicy == ConflictPolicy.Overwrite
        val target = app.storage.targetOf(job.saveTarget)
        val saved = try {
            app.storage.publishToGallery(file, name, overwrite, job.saveDir, target)
        } catch (e: SecurityException) {
            // A copy made by a previous install can't be replaced by this one; keep both.
            app.storage.publishToGallery(file, name, overwrite = false, subfolder = job.saveDir, target = target)
        }
        app.copies.recordSaved(media, job.version, saved, job.cameraSerial)
        return saved
    }

    // --- shared building blocks (also used by uploads) ---

    internal fun cameraApi(): CameraApi =
        app.camera.api ?: throw WaitException("Waiting for the camera's WiFi")

    /**
     * Downloads the clip (or for preview jobs its preview) into [target] from where [sourceOf] says:
     * the camera, or a copy on a server. Copies on the phone are used where they are, not fetched.
     */
    internal suspend fun fetch(job: JobEntity, media: CameraMedia, target: File): File = when (val src = sourceOf(job)) {
        is ClipSource.Server -> fetchFromServer(job, src.copy, target)
        else -> fetchFromCamera(job, media, target)
    }

    /** Downloads (resuming) a copy OpenPro uploaded, from its server into [target]. */
    private suspend fun fetchFromServer(job: JobEntity, copy: com.openpro.copies.CopyEntity, target: File): File {
        val server = app.servers.get(copy.serverId) ?: throw PermanentException("The server with this copy was removed under Servers")
        val meter = Meter(job.id, "Downloading from ${server.name}", toInternet = true)
        val file = try {
            app.servers.client(server).download(copy.location, target) { done, total -> meter.update(done, total) }
        } catch (e: java.io.FileNotFoundException) {
            app.copies.remove(copy)
            throw PermanentException("${copy.fileName} is no longer on ${server.name}")
        }
        if (copy.sizeBytes > 0 && file.length() != copy.sizeBytes) throw TransientException("Download from ${server.name} incomplete")
        repo.note(job.id, "Downloaded ${copy.location} from ${server.name}")
        return file
    }

    /** Downloads (resuming) from the camera into [target], reporting progress: the clip, or for preview jobs its preview. */
    internal suspend fun fetchFromCamera(job: JobEntity, media: CameraMedia, target: File): File {
        val preview = job.variant == JobVariant.Preview
        // One stream at a time from the camera: if a preview or photo has it, wait for that.
        val lease = CameraStreams.tryAcquire(CameraStreams.Who.Batch, "the batch is downloading ${media.name}")
            ?: throw WaitException(cameraBusy())
        try {
            val meter = Meter(job.id, if (preview) "Downloading the preview from camera" else "Downloading from camera")
            return app.downloader.download(cameraApi(), media, target, { done, total -> meter.update(done, total) }, preview)
                .also { if (job.fetchBytes > 0 && it.length() != job.fetchBytes) throw TransientException("Download incomplete") }
        } finally {
            CameraStreams.release(lease)
        }
    }

    /** Why a job that needs the camera's Wi-Fi waits: something else is streaming from it. */
    private fun cameraBusy(): String =
        "Waiting for the camera: ${CameraStreams.holder.value?.doing ?: "it's busy"}. It continues when that's closed."

    /** Renders a stabilized version with GoPro's engine; returns the output file. */
    internal suspend fun render(job: JobEntity, media: CameraMedia): File {
        if (!app.quik.engineAvailable) throw PermanentException("Stabilizing needs GoPro's engine: add it under About › Add stabilization")
        // The engine runs in its own process and opens the clip by path. A gallery copy (or OpenPro's
        // own download) it can open as it is; a copy in a picked folder (which OpenPro reads through
        // Android, not by path) is copied to working storage first, and a server's copy downloaded there.
        var temporary: File? = null
        val local: File = when (val src = sourceOf(job)) {
            // Downloaded first, never streamed into the engine: the engine's own connections would
            // bypass the one-stream-at-a-time rule, and two streams at once can crash the camera.
            ClipSource.Camera -> fetchFromCamera(job, media, app.storage.engineSourceFile(media)).also { temporary = it }
            is ClipSource.Phone -> {
                val copy = src.copy
                val direct = when {
                    copy == null -> app.copies.phoneFile(media, ClipVersion.Original)
                    copy.place == com.openpro.copies.CopyPlace.Gallery -> app.storage.galleryFile(android.net.Uri.parse(copy.location), copy.sizeBytes)
                    else -> null
                }
                direct ?: (if (copy != null) app.copies.openCopy(copy) else app.copies.openPhoneFile(media, ClipVersion.Original))
                    ?.use { copyForEngine(job, media, it) }?.also { temporary = it }
                    ?: throw PermanentException("The copy on the phone to stabilize from can't be opened")
            }
            is ClipSource.Server -> fetchFromServer(job, src.copy, app.storage.engineSourceFile(media)).also { temporary = it }
        }
        try {
            return render(job, media, local)
        } finally {
            temporary?.delete()
        }
    }

    /** Copies a clip from where it's saved to working storage, for the stabilizing engine. */
    private suspend fun copyForEngine(job: JobEntity, media: CameraMedia, from: com.openpro.copies.OpenFile): File = withContext(Dispatchers.IO) {
        val to = app.storage.engineSourceFile(media)
        val meter = Meter(job.id, "Copying the clip from its folder to stabilize it")
        from.file.inputStream().use { input ->
            to.outputStream().use { out ->
                val buffer = ByteArray(1 shl 20)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    meter.update(done, from.length)
                }
            }
        }
        if (from.length > 0 && to.length() != from.length) {
            to.delete()
            throw TransientException("Copying the clip from its folder stopped part way")
        }
        to
    }

    private suspend fun render(job: JobEntity, media: CameraMedia, local: File): File {
        val out = app.storage.renderFile(media)
        out.delete() // a partial render can't be resumed
        val quikJob = QuikJob(
            id = "batch-${job.id}-${System.currentTimeMillis()}",
            kind = JobKind.Export,
            source = JobSource(localPath = local.path),
            stabilization = job.stabilization ?: StabilizationChoice.Suggested,
            outputPath = out.path,
            is360 = media.extension == "360",
        )
        val source = "Stabilizing"
        repo.note(job.id, "$source (${local.name})")
        repo.setLive(job.id, LiveProgress(source, 0, 0, isBytes = false))
        runningRenderId = quikJob.id
        val result = coroutineScope {
            val watcher = launch {
                app.quik.progress.collect { all ->
                    all[quikJob.id]?.let { p ->
                        // Where it reads from, then what the engine is doing ("Rendering … · 820 MB written").
                        // Past 100% it's finishing the file: a moving bar, with the size still growing.
                        val label = if (p.message.isEmpty()) source else "$source · ${p.message}"
                        val finishing = p.progress >= 0.999f
                        repo.setLive(job.id, LiveProgress(label, if (finishing) 0 else (p.progress * 1000).toLong(), if (finishing) 0 else 1000, isBytes = false))
                    }
                }
            }
            try {
                withContext(Dispatchers.Main) { app.quik.run(quikJob, null) }
            } finally {
                watcher.cancel()
                runningRenderId = null
            }
        }
        result.error?.let {
            out.delete()
            if (it == "cancelled") throw CancellationException("cancelled")
            if (it.startsWith(com.openpro.quik.QuikException.UNSUPPORTED)) throw PermanentException(it)
            throw TransientException("Stabilizing failed: $it")
        }
        return out
    }

    /** Name for the copy saved or uploaded, by recording time: see [ClipNames]. */
    fun savedName(media: CameraMedia, version: ClipVersion) = ClipNames.saved(media, version)

    /** Names an earlier copy may have been saved or uploaded under, preferred first. */
    fun savedNames(media: CameraMedia, version: ClipVersion) = ClipNames.candidates(media, version)

    /** Throttled progress reporting: live UI every update, the database about once a second. */
    internal inner class Meter(private val jobId: String, private val label: String, private val toInternet: Boolean = false) {
        private var lastDb = 0L
        // Speed is measured over windows of at least a second: updates come every few kilobytes, often
        // under a millisecond apart, and measuring between those gave wild speeds (and "0s left").
        private var windowStart = 0L
        private var windowBytes = -1L
        private var rate = 0L

        fun update(done: Long, total: Long) {
            val now = System.currentTimeMillis()
            if (windowBytes < 0 || done < windowBytes) {
                windowStart = now
                windowBytes = done
            } else if (now - windowStart >= RATE_WINDOW_MS) {
                val instant = (done - windowBytes) * 1000 / (now - windowStart)
                rate = if (rate == 0L) instant else (rate * 3 + instant) / 4
                windowStart = now
                windowBytes = done
            }
            repo.setLive(jobId, LiveProgress(label, done, total, rate, toInternet = toInternet))
            if (now - lastDb > 1000) {
                lastDb = now
                runBlocking { repo.progress(jobId, done, total) }
            }
        }
    }

    // --- cancellation ---

    /** Set while the running job is being stopped for a pause, so it's put back in the queue quietly. */
    @Volatile private var pausing = false

    /** Stops the running job for a pause, unless it's stabilizing (no network needed, and a render can't resume). */
    private fun interrupt() {
        val current = running ?: return
        if (runningRenderId != null) return
        pausing = true
        current.second.cancel(CancellationException("paused"))
    }

    private fun cancel(jobId: String) {
        val current = running ?: return
        if (current.first != jobId) return
        runningRenderId?.let { app.quik.cancel(it) }
        current.second.cancel(CancellationException("cancelled by user"))
    }

    private fun cleanupAfterCancel(job: JobEntity) {
        // A deletion has no working files, and the clip's may belong to its download.
        if (job.action == JobAction.Delete) return
        val media = job.toMedia()
        job.renderPath?.let { File(it).delete() }
        // The work volume may be gone (card removed); then there's nothing reachable to clean.
        runCatching {
            File(app.storage.originalFile(media).path + ".part").delete()
            File(app.storage.stagingFile(media).path + ".part").delete()
            app.storage.stagingFile(media).delete()
            app.storage.renderFile(media).delete()
            File(app.storage.previewStagingFile(media).path + ".part").delete()
            app.storage.previewStagingFile(media).delete()
        }
    }

    companion object {
        private const val TAG = "BatchRunner"
        /** How long to wait for the phone to rejoin its own Wi-Fi after leaving the camera's. */
        private const val INTERNET_WAIT_MS = 45_000L
        /** The shortest stretch a transfer's speed is measured over. */
        private const val RATE_WINDOW_MS = 1_000L
    }
}
