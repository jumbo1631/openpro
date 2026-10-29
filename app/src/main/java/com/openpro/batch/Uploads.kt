package com.openpro.batch

import com.openpro.OpenProApp
import com.openpro.media.CameraMedia
import com.openpro.media.ClipVersion
import com.openpro.server.DavEntry
import com.openpro.server.ServerClient
import com.openpro.server.ServerProfile
import com.openpro.server.ServerType
import com.openpro.server.UntrustedCertificateException
import com.openpro.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.TimeZone
import java.util.UUID

/**
 * Upload jobs, including "Download & upload" ([JobEntity.keepOnPhone]):
 * (download or stabilize) → upload → verify → (save on the phone) → clean up → (delete from camera).
 *
 * - **Fetch** (originals and previews): use the copy already on the phone if there is one
 *   (OpenPro's download, or the copy in its gallery folder); else download it on the camera's
 *   Wi-Fi, to the staging folder, or for Download & upload of an original to where downloads go.
 * - **Upload**: pick the remote name (conflict policy), then send the local file. Nextcloud gets a
 *   resumable chunked upload whose session id and chunks survive interruptions; generic WebDAV
 *   gets one PUT.
 * - **Verify**: the server's copy must have the expected size, and the same SHA1 where Nextcloud
 *   reports one. Otherwise the upload is redone. Nothing is deleted anywhere before this passes.
 * - **Publish** (Download & upload): save the file on the phone, as a download would be.
 * - **Cleanup**: always delete staged and rendered working files; if asked, the clip's copy on the
 *   phone too.
 * - **DeleteFromCamera** (if asked): delete the clip from the camera, after checking the camera's
 *   file is still the one that was uploaded.
 */
object Uploads {
    /** Upload actions are offered in the UI. */
    const val available = true

    /** The file to upload: the rendered or staged file, else the copy already on the phone. Close it once done. */
    private fun localSource(app: OpenProApp, job: JobEntity): com.openpro.copies.OpenFile? =
        (job.renderPath?.let(::File)?.takeIf { it.exists() } ?: job.stagingPath?.let(::File)?.takeIf { it.exists() })
            ?.let { com.openpro.copies.OpenFile(it, it.length(), null) }
            ?: if (job.variant != JobVariant.Stabilized) app.copies.openPhoneFile(job.toMedia(), phoneVersion(job)) else null

    /** The version of the clip on the phone an upload can send as it is: the original, or the preview. */
    private fun phoneVersion(job: JobEntity) = if (job.variant == JobVariant.Preview) ClipVersion.Preview else ClipVersion.Original

    // --- waiting ---

    private val reachability = mutableMapOf<String, Pair<Long, String?>>()

    /** Why an upload job can't run now: its server is gone or can't be reached. */
    internal suspend fun blocker(app: OpenProApp, job: JobEntity, config: BatchConfig): String? {
        if (job.action == JobAction.Delete && job.step == Step.DeleteRemote) {
            val server = app.servers.get(job.serverId) ?: return "The server for this deletion was removed. Remove this job."
            if (app.servers.password(server.id) == null) return "Sign in to ${server.name} again under Servers."
            return reachable(app, server)
        }
        if (job.action != JobAction.Upload) return null
        val server = app.servers.get(job.serverId)
            ?: return "The server for this upload was removed. Set it up again under Servers, or remove this job."
        if (job.step != Step.Upload && job.step != Step.Verify) return null
        if (app.servers.password(server.id) == null) return "Sign in to ${server.name} again under Servers. Uploads continue once you have."
        return reachable(app, server)
    }

    /** Checks the server answers at all, at most every 30 s per server. Sign-in problems are left to the step. */
    private suspend fun reachable(app: OpenProApp, server: ServerProfile): String? {
        val now = System.currentTimeMillis()
        reachability[server.id]?.let { (at, verdict) -> if (now - at < 30_000) return verdict }
        val verdict = try {
            app.servers.client(server).stat(server.folder)
            null
        } catch (e: HttpStatusException) {
            null // reachable; the step reports the HTTP problem properly
        } catch (e: UntrustedCertificateException) {
            null
        } catch (e: IOException) {
            "Can't reach ${server.name} (${BatchErrors.describe(e)}). Uploads continue when it answers" +
                if (app.net.internet.value.vpn) "; if it's only reachable through your VPN, check the VPN is connected." else "."
        }
        reachability[server.id] = now to verdict
        return verdict
    }

    // --- steps ---

    internal suspend fun step(app: OpenProApp, runner: BatchRunner, job: JobEntity, config: BatchConfig): JobEntity {
        val server = app.servers.get(job.serverId)
            ?: throw PermanentException("The server for this upload was removed under Servers")
        val media = job.toMedia()
        return when (job.step) {
            Step.Fetch -> fetch(app, runner, job, config, media)
            Step.Render -> job.copy(step = Step.Upload, renderPath = runner.render(job, media).path)
            Step.Upload -> upload(app, runner, job, config, server, media)
            Step.Verify -> verify(app, job, server)
            Step.Publish -> saveOnPhone(app, runner, job, config, media)
            Step.Cleanup -> cleanup(app, job, server, media, verified = true)
            Step.DeleteFromCamera -> deleteFromCamera(app, runner, job)
            else -> job.copy(step = Step.Finished)
        }
    }

    private suspend fun fetch(app: OpenProApp, runner: BatchRunner, job: JobEntity, config: BatchConfig, media: CameraMedia): JobEntity {
        val repo = app.batch
        // Downloaded already (in the gallery, or a picked folder): upload that, with nothing to fetch.
        if (job.variant != JobVariant.Stabilized && app.copies.hasPhoneCopy(media, phoneVersion(job))) {
            repo.note(job.id, "Already on the phone: uploading that copy, nothing to fetch from the camera")
            return job.copy(step = Step.Upload)
        }
        if (job.variant == JobVariant.Preview) {
            if (!media.hasLrv) throw PermanentException("The camera has no preview for this clip")
            // Staged even for Download & upload: Publish saves it from there.
            val file = runner.fetch(job, media, app.storage.previewStagingFile(media))
            return job.copy(step = Step.Upload, stagingPath = file.path)
        }
        if (job.keepOnPhone) {
            // Download & upload: download it as a download would, then upload that same file.
            runner.fetch(job, media, app.storage.originalFile(media))
            return job.copy(step = Step.Upload)
        }
        val file = runner.fetch(job, media, app.storage.stagingFile(media))
        return job.copy(step = Step.Upload, stagingPath = file.path)
    }

    private suspend fun upload(
        app: OpenProApp, runner: BatchRunner, start: JobEntity, config: BatchConfig, server: ServerProfile, media: CameraMedia,
    ): JobEntity {
        val opened = localSource(app, start) ?: run {
            // The staged or rendered file is gone (deleted, or its card removed): redo that part.
            app.batch.note(start.id, "The prepared file is missing; preparing it again")
            return start.copy(step = if (start.variant == JobVariant.Stabilized) Step.Render else Step.Fetch, stagingPath = null, renderPath = null)
        }
        return opened.use { uploadFrom(app, runner, start, config, server, media, it.file, it.length) }
    }

    private suspend fun uploadFrom(
        app: OpenProApp, runner: BatchRunner, start: JobEntity, config: BatchConfig, server: ServerProfile, media: CameraMedia,
        source: File, length: Long,
    ): JobEntity {
        val repo = app.batch
        var job = start
        val client = app.servers.client(server)
        val name = runner.savedName(media, job.version)

        // SHA1 for Nextcloud's integrity check.
        if (job.checksum == null && server.type == ServerType.Nextcloud) {
            val meter = runner.Meter(job.id, "Checking the file before upload")
            val sha = withContext(Dispatchers.IO) { ServerClient.sha1(source) { meter.update(it, length) } }
            job = job.copy(checksum = sha)
            checkpoint(app, job)
        }

        if (job.remotePath == null) {
            val dir = remoteDir(server, job)
            client.mkdirs(dir)
            if (config.conflictPolicy == ConflictPolicy.SkipIdentical) {
                identicalOnServer(app, runner, job, client, server, media, dir, length)?.let { (path, existing) ->
                    val checksummed = existing.sha1 != null && job.checksum != null
                    repo.note(job.id, "Already on ${server.name} as $path with the same ${if (checksummed) "size and checksum" else "size"}; skipped")
                    app.copies.recordUpload(media, job.version, server.id, server.name, path, length, existing.sha1, job.cameraSerial)
                    // A size-only match might be a different file: never delete anything on that basis.
                    val matched = job.copy(remotePath = path, bytesTotal = length)
                    if (!job.keepOnPhone) {
                        return cleanup(app, matched, server, media, verified = checksummed).copy(resultLocation = "Already on ${server.name}: $path")
                    }
                    if (job.deleteFromCamera && !checksummed) repo.note(job.id, "Will keep the clip on the camera: the server's file matched by size only")
                    return matched.copy(step = Step.Publish, deleteFromCamera = job.deleteFromCamera && checksummed)
                }
            }
            val existing = client.stat("$dir/$name")
            val path = when {
                existing == null || config.conflictPolicy == ConflictPolicy.Overwrite -> "$dir/$name"
                else -> freeName(client, dir, name).also {
                    repo.note(job.id, "A different file named $name is on the server; uploading as ${it.substringAfterLast('/')}")
                }
            }
            job = job.copy(remotePath = path, bytesTotal = length)
            checkpoint(app, job, "Uploading to ${server.name}: $path")
        } else if (job.remoteUploadId != null || server.type == ServerType.WebDav) {
            // Resuming. A file of the right size at the path we chose is ours, from an attempt that
            // finished on the server but wasn't recorded here (e.g. app killed during the last step).
            client.stat(job.remotePath!!)?.takeIf { it.size == length }?.let {
                repo.note(job.id, "The server already has the complete file from the previous attempt")
                return job.copy(step = Step.Verify, remoteUploadId = null)
            }
        }
        val remote = job.remotePath!!
        val mtime = realEpochSec(media.createdSec)
        val meter = runner.Meter(job.id, "Uploading to ${server.name}", toInternet = true)
        when (server.type) {
            ServerType.Nextcloud -> {
                job = chunked(app, runner, job, client, source, length, remote, config, meter)
                client.chunkedFinish(job.remoteUploadId!!, remote, length, job.checksum, mtime, overwrite = config.conflictPolicy == ConflictPolicy.Overwrite)
            }
            ServerType.WebDav -> client.putFile(remote, source, null, mtime, length) { meter.update(it, length) }
        }
        return job.copy(step = Step.Verify, remoteUploadId = null)
    }

    /**
     * An identical copy of the job's file already on the server (same size, and checksum where both
     * are known), looked for where it's most likely to be:
     *  1. where the copies index says it was uploaded (perhaps under an earlier folder layout);
     *  2. where the current layout puts it, under its name or (if saved before names led with the
     *     recording time) the camera's;
     *  3. anywhere in the server's upload folder, by name, if the server can search (Nextcloud),
     *     e.g. after it was moved there by hand.
     * Index records whose file is gone are dropped on the way.
     */
    private suspend fun identicalOnServer(
        app: OpenProApp, runner: BatchRunner, job: JobEntity, client: ServerClient, server: ServerProfile,
        media: CameraMedia, dir: String, length: Long,
    ): Pair<String, DavEntry>? {
        fun same(e: DavEntry) = !e.isDirectory && e.size == length && (e.sha1 == null || job.checksum == null || e.sha1 == job.checksum)
        for (copy in app.copies.remote(media, job.version, server.id)) {
            val e = client.stat(copy.location)
            if (e == null) app.copies.remove(copy) else if (same(e)) return copy.location to e
        }
        val names = runner.savedNames(media, job.version)
        for (n in names) client.stat("$dir/$n")?.takeIf(::same)?.let { return "$dir/$n" to it }
        return runCatching { client.findByName(server.folder, names) }.getOrNull()?.firstOrNull { same(it.second) }
    }

    /** Sends the missing chunks of a Nextcloud chunked upload, resuming a previous session if it still exists. */
    private suspend fun chunked(
        app: OpenProApp, runner: BatchRunner, start: JobEntity, client: ServerClient, source: File, length: Long,
        remote: String, config: BatchConfig, meter: BatchRunner.Meter,
    ): JobEntity {
        var job = start
        var uploadId = job.remoteUploadId
        var existing = uploadId?.let { client.chunkedExisting(it) }
        if (uploadId == null || existing == null) {
            if (uploadId != null) app.batch.note(job.id, "The server discarded the unfinished upload; starting it again")
            uploadId = "openpro-" + UUID.randomUUID()
            client.chunkedBegin(uploadId, remote)
            existing = emptyMap()
            job = job.copy(remoteUploadId = uploadId)
            checkpoint(app, job)
        }
        val chunk = config.chunkSizeMb.toLong() * 1024 * 1024
        val count = ((length + chunk - 1) / chunk).toInt()
        var done = (1..count).sumOf { i -> val len = minOf(chunk, length - (i - 1) * chunk); if (existing[i] == len) len else 0L }
        if (done > 0) app.batch.note(job.id, "Resuming: ${formatBytes(done)} already on the server")
        for (i in 1..count) {
            val offset = (i - 1) * chunk
            val len = minOf(chunk, length - offset)
            if (existing[i] == len) continue
            val base = done
            val body = ServerClient.FileRegionBody(source, offset, len) { meter.update(base + it, length) }
            client.chunkedPut(uploadId, i, remote, body, length)
            done += len
            meter.update(done, length)
        }
        return job
    }

    private suspend fun verify(app: OpenProApp, job: JobEntity, server: ServerProfile): JobEntity {
        val remote = job.remotePath ?: throw PermanentException("No remote path recorded; retry to upload again")
        val entry = app.servers.client(server).stat(remote)
        val problem = when {
            entry == null -> "The file isn't on the server"
            job.bytesTotal > 0 && entry.size >= 0 && entry.size != job.bytesTotal ->
                "The server has ${formatBytes(entry.size)}, expected ${formatBytes(job.bytesTotal)}"
            job.checksum != null && entry.sha1 != null && entry.sha1 != job.checksum -> "The server's checksum doesn't match"
            else -> null
        }
        if (problem != null) {
            // Back to Upload; this counts as a failed attempt.
            app.batch.saveIfRunning(job.copy(step = Step.Upload, remoteUploadId = null), "Verification failed: $problem")
            throw TransientException("$problem; uploading again")
        }
        val how = if (job.checksum != null && entry?.sha1 != null) "size and SHA1 match" else "size matches (the server reports no checksum)"
        app.batch.note(job.id, "Verified on ${server.name}: $how")
        app.copies.recordUpload(job.toMedia(), job.version, server.id, server.name, remote, job.bytesTotal, entry?.sha1 ?: job.checksum, job.cameraSerial)
        return job.copy(step = if (job.keepOnPhone) Step.Publish else Step.Cleanup)
    }

    /** Download & upload: save the clip on the phone, as a download would be (after the upload is safe). */
    private suspend fun saveOnPhone(app: OpenProApp, runner: BatchRunner, job: JobEntity, config: BatchConfig, media: CameraMedia): JobEntity {
        val next = job.copy(step = Step.Cleanup)
        if (job.variant != JobVariant.Original) {
            // A render or a staged preview: saved where finished videos go, unless it's already there.
            val prepared = (job.renderPath ?: job.stagingPath)?.let(::File)?.takeIf { it.exists() } ?: return next
            val saved = runner.publish(job, media, prepared)
            app.batch.note(job.id, "Saved ${saved.fileName} to ${saved.label}")
            return next.copy(renderPath = null, stagingPath = null) // moved, not a working file any more
        }
        // Originals: saved like downloads.
        val original = app.storage.localOriginal(media) ?: return next // already in the gallery
        val saved = runner.publish(job, media, original)
        app.storage.forgetOriginal(media)
        app.batch.note(job.id, "Saved ${saved.fileName} to ${saved.label}")
        return next
    }

    /**
     * Always deletes the staging and render files. If the user asked for it and the server's copy
     * is [verified] (our own upload, or a checksum match), also the clip's copy on the phone; then
     * on to deleting it from the camera, if that was asked for too.
     */
    private suspend fun cleanup(app: OpenProApp, job: JobEntity, server: ServerProfile, media: CameraMedia, verified: Boolean): JobEntity {
        job.stagingPath?.let { File(it).delete() }
        job.renderPath?.let { File(it).delete() }
        if (job.deletePhoneCopy && !job.keepOnPhone) {
            if (verified) deletePhoneCopy(app, job, media) else app.batch.note(job.id, "Kept the phone's copy: the server's file matched by size only")
        }
        val done = job.copy(stagingPath = null, renderPath = null, resultLocation = "${server.name}: ${job.remotePath}")
        return when {
            !job.deleteFromCamera -> done.copy(step = Step.Finished)
            verified -> done.copy(step = Step.DeleteFromCamera)
            else -> {
                app.batch.note(job.id, "Kept the clip on the camera: the server's file matched by size only")
                done.copy(step = Step.Finished, deleteFromCamera = false)
            }
        }
    }

    /**
     * Deletes the clip from the camera (the last step of uploads and downloads that asked for it),
     * once the camera's file is confirmed to be the one that was saved (same size and recording
     * time): the camera reuses names after a card is formatted.
     */
    internal suspend fun deleteFromCamera(app: OpenProApp, runner: BatchRunner, job: JobEntity): JobEntity {
        val api = runner.cameraApi()
        val onCamera = api.mediaList().firstOrNull { it.folder == job.folder && it.name == job.name }
        return when {
            onCamera == null -> {
                app.catalogue.leftCamera(job.mediaId)
                app.batch.note(job.id, "Already gone from the camera")
                job.copy(step = Step.Finished)
            }
            onCamera.sizeBytes != job.sizeBytes || onCamera.createdSec != job.createdSec -> {
                app.batch.note(job.id, "Kept on the camera: the file there now isn't the same clip")
                job.copy(step = Step.Finished, deleteFromCamera = false)
            }
            else -> {
                api.deleteFile(onCamera)
                app.catalogue.leftCamera(job.mediaId)
                app.batch.note(job.id, "Deleted ${job.name} from the camera")
                job.copy(step = Step.Finished)
            }
        }
    }

    /**
     * The clip's copy on the phone: OpenPro's own download, and the copy in its gallery folder.
     * Only files the same size as the one uploaded (job.bytesTotal), so a different clip that
     * happens to share the name (the camera reuses names after a card is formatted) is left alone.
     */
    private suspend fun deletePhoneCopy(app: OpenProApp, job: JobEntity, media: CameraMedia) {
        val deleted = mutableListOf<String>()
        if (job.variant == JobVariant.Original) {
            app.storage.localOriginal(media)?.takeIf { it.length() == job.bytesTotal }?.let { file ->
                if (file.delete()) deleted += "OpenPro's download"
                app.storage.forgetOriginal(media)
            }
        }
        // Saved copies of the same version, by the copies index, and only one the size of the file uploaded.
        for (copy in app.copies.local(media, job.version).filter { it.sizeBytes == job.bytesTotal }) {
            when (app.storage.deleteLocalCopy(copy.place == com.openpro.copies.CopyPlace.Gallery, android.net.Uri.parse(copy.location))) {
                true -> { deleted += "${copy.fileName} in ${copy.placeLabel}"; app.copies.remove(copy) }
                null -> app.copies.remove(copy)
                false -> app.batch.note(job.id, "Couldn't delete ${copy.fileName} from ${copy.placeLabel}: Android needs your permission")
            }
        }
        if (deleted.isNotEmpty()) app.batch.note(job.id, "Deleted the phone's copy (${deleted.joinToString(" and ")}) now that the server has it")
    }

    private suspend fun checkpoint(app: OpenProApp, job: JobEntity, message: String? = null) {
        if (!app.batch.saveIfRunning(job, message)) throw kotlinx.coroutines.CancellationException("cancelled")
    }

    /** The server's upload folder, then the job's folders (from the upload layout, set when it was queued). */
    fun remoteDir(server: ServerProfile, job: JobEntity): String =
        "/" + listOfNotNull(server.folder.trim('/').ifEmpty { null }, job.uploadDir).joinToString("/")

    /** "name (1).MP4", "name (2).MP4", … whichever isn't taken in [dir]. */
    private suspend fun freeName(client: ServerClient, dir: String, name: String): String {
        val taken = client.list(dir).map { it.name }.toSet()
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val free = (1..9999).map { "$base ($it)$ext" }.first { it !in taken }
        return "$dir/$free"
    }

    /** Camera timestamps are local wall-clock time stored as if UTC; convert for the server's file date. */
    private fun realEpochSec(cameraSec: Long): Long =
        if (cameraSec <= 0) 0 else cameraSec - TimeZone.getDefault().getOffset(cameraSec * 1000) / 1000
}
