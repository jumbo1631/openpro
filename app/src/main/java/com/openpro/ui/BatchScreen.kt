package com.openpro.ui

import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Cancel

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.width

import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import com.openpro.batch.JobAction
import com.openpro.batch.JobEntity
import com.openpro.batch.JobState
import com.openpro.batch.JobVariant
import com.openpro.batch.LiveProgress
import com.openpro.batch.Step
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Everything the batch is doing, straight from its database (so it's right after a restart):
 * what runs now, what waits and why, what failed and why, and what finished.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(vm: AppViewModel, onServers: () -> Unit, onBack: () -> Unit) {
    val jobs by vm.batchJobs.collectAsState()
    val live by vm.batch.live.collectAsState()
    val config by vm.batchConfig.collectAsState()
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Screen("Batch", onBack = onBack, actions = {
        IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Batch settings") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Retry all failed") }, onClick = { menu = false; scope.launch { vm.batch.retryAllFailed() } })
                DropdownMenuItem(text = { Text("Servers…") }, onClick = { menu = false; onServers() })
            }
        }
    }) { padding ->
        val sections = listOf(
            "Running" to jobs.filter { it.state == JobState.Running },
            "Queue" to jobs.filter { it.state == JobState.Queued },
            "Waiting" to jobs.filter { it.state == JobState.Waiting },
            "Failed" to jobs.filter { it.state == JobState.Failed },
            "Done" to jobs.filter { it.state == JobState.Done || it.state == JobState.Cancelled }.sortedByDescending { it.updatedAt },
        ).filter { it.second.isNotEmpty() }

        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The summary, with pause/resume right beside it as a big button, the same height.
            item {
                Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { BatchSummary(vm, jobs, config.paused, config.uploadStrategy.label) }
                    PauseButton(config.paused, Modifier.fillMaxHeight().width(96.dp)) { vm.batch.setPaused(!config.paused) }
                }
            }
            if (jobs.isEmpty()) {
                item {
                    Text(
                        "Nothing queued. In the gallery, long-press clips to select them, then choose Download or Upload.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            for ((title, list) in sections) {
                item(key = "h:$title") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$title (${list.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (title == "Done") TextButton(onClick = { scope.launch { vm.batch.clearFinished() } }) { Text("Clear all") }
                    }
                }
                items(list, key = { it.id }) { job ->
                    JobCard(job, live[job.id], onClick = { detail = job.id }, vm = vm)
                }
            }
        }
    }

    detail?.let { id ->
        val job = jobs.firstOrNull { it.id == id }
        if (job == null) {
            detail = null
        } else {
            ModalBottomSheet(onDismissRequest = { detail = null }) { JobDetail(vm, job, live[job.id]) }
        }
    }
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) { BatchSettingsSheet(vm) }
    }
}

@Composable
private fun BatchSummary(vm: AppViewModel, jobs: List<JobEntity>, paused: Boolean, strategy: String) = BatchSummaryCard(vm, jobs, paused, strategy)

/**
 * Pause or resume the whole queue: a big button beside the summary. Resume is the filled, primary
 * one, since a paused queue is waiting on you.
 */
@Composable
private fun PauseButton(paused: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(28.dp))
            Text(if (paused) "Resume" else "Pause", style = MaterialTheme.typography.labelLarge)
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)
    if (paused) {
        androidx.compose.material3.Button(onClick = onClick, modifier, shape = shape, contentPadding = padding, content = content)
    } else {
        androidx.compose.material3.FilledTonalButton(onClick = onClick, modifier, shape = shape, contentPadding = padding, content = content)
    }
}

@Composable
private fun BatchSummaryCard(vm: AppViewModel, jobs: List<JobEntity>, paused: Boolean, strategy: String) {
    val net by vm.net.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val active = jobs.count { it.isActive }
            val failed = jobs.count { it.state == JobState.Failed }
            val done = jobs.count { it.state == JobState.Done }
            Text(
                when {
                    paused -> "Paused · $active left"
                    active > 0 -> "$active to go · $done done" + if (failed > 0) " · $failed failed" else ""
                    else -> "$done done" + if (failed > 0) " · $failed failed" else ""
                },
                style = MaterialTheme.typography.titleMedium,
            )
            net.switching?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            Text(strategy, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The steps a job goes through, in order, with a label for each. */
private fun pipeline(job: JobEntity): List<Pair<Step, String>> = buildList {
    val prepare = when (job.variant) {
        JobVariant.Stabilized -> Step.Render to "Stabilize"
        JobVariant.Preview -> Step.Fetch to "Download preview"
        JobVariant.Original -> Step.Fetch to "Download"
    }
    when (job.action) {
        JobAction.Delete -> add(job.step to if (job.serverId != null) "Delete from server" else "Delete from camera")
        JobAction.Download -> {
            add(prepare)
            add(Step.Publish to "Save to gallery")
            if (job.deleteFromCamera) add(Step.DeleteFromCamera to "Delete from camera")
        }
        JobAction.Upload -> {
            add(prepare)
            add(Step.Upload to "Upload")
            add(Step.Verify to "Verify")
            if (job.keepOnPhone) add(Step.Publish to "Save to phone")
            if (job.deleteFromCamera) add(Step.DeleteFromCamera to "Delete from camera")
        }
    }
}

/** The job's steps as chips; they wrap onto another line rather than squeezing when space runs out. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StepPipeline(job: JobEntity) {
    val steps = pipeline(job)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        steps.forEachIndexed { i, (step, label) ->
            val done = job.step == Step.Finished || job.step.ordinal > step.ordinal
            val current = job.step == step && job.isActive
            val failedHere = job.step == step && job.state == JobState.Failed
            val blue = MaterialTheme.colorScheme.primary
            val shape = RoundedCornerShape(4.dp)
            // Done: filled blue with a tick. Current: outlined in blue. Still to come: plain text.
            val chip = when {
                failedHere -> Modifier.background(MaterialTheme.colorScheme.error, shape)
                done -> Modifier.background(blue, shape)
                current -> Modifier.background(blue.copy(alpha = 0.16f), shape).border(1.dp, blue, shape)
                else -> Modifier
            }
            // A chip and the arrow after it wrap together; each chip stays one line high.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    (if (done) "✓ " else "") + label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                    color = when {
                        done || failedHere -> Color.Black
                        current -> blue
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = chip.padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (i < steps.lastIndex) Text("›", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

internal fun destination(vm: AppViewModel, job: JobEntity) = when (job.action) {
    JobAction.Download -> "to " + vm.storage.saveTargetLabel(vm.storage.targetOf(job.saveTarget))
    JobAction.Upload -> "to " + (vm.servers.get(job.serverId)?.name ?: "a removed server")
    JobAction.Delete -> if (job.serverId != null) "on " + (vm.servers.get(job.serverId)?.name ?: "a removed server") else "on the camera"
}

@Composable
private fun JobCard(job: JobEntity, live: LiveProgress?, onClick: () -> Unit, vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val container = when (job.state) {
        JobState.Failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                JobThumbnail(job, vm)
                Column(Modifier.weight(1f)) {
                    // Actions trail the title, so the line under it has the card's full width.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(job.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                        // A little above the title's centre line, level with its capitals.
                        JobActions(job, vm, scope, Modifier.offset(y = (-4).dp))
                    }
                    ScrollingLine("${job.title} ${destination(vm, job)} · ${formatBytes(job.sizeBytes)}")
                }
            }
            // What it's doing right now (e.g. "Stabilizing, streaming from the camera").
            JobProgress(job, live)
            job.waitingReason?.takeIf { job.state == JobState.Waiting }?.let {
                ScrollingLine(it, MaterialTheme.colorScheme.tertiary)
            }
            failure(job)?.let { ScrollingLine(it, MaterialTheme.colorScheme.error) }
            if (job.state == JobState.Queued && job.nextRetryAt > System.currentTimeMillis()) {
                Text("Next try at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(job.nextRetryAt))}", style = MaterialTheme.typography.bodySmall)
            }
            // Where it went, in a word; the full path is in the job's sheet.
            if (job.state == JobState.Done) ScrollingLine(resultSummary(job))
            // The steps, with how long it has spent processing at the end.
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { StepPipeline(job) }
                JobTimer(job)
            }
        }
    }
}

/** One line of a job's text; longer text scrolls slowly across instead of wrapping. */
@Composable
private fun ScrollingLine(text: String, color: Color = Color.Unspecified) {
    Text(
        text,
        Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 2_000, velocity = 24.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
    )
}

/** The job's last error, with the attempt it failed on when it's retrying by itself; null if none to show. */
private fun failure(job: JobEntity): String? =
    job.error?.takeIf { job.state == JobState.Failed || job.state == JobState.Queued && job.attempts > 0 }?.let {
        (if (job.state == JobState.Queued) "Attempt ${job.attempts} failed, retrying: " else "") + it
    }

/** Whether a finished job saved or uploaded a file, rather than deleting one or finding it already there. */
private fun JobEntity.saved() = action != JobAction.Delete && resultLocation?.startsWith("Already") == false

/**
 * A finished job in a few words: "Saved to Phone", "Already on Nextcloud", "Deleted from the camera".
 * [resultLocation] is "<place>: <path>", and a place can name its folder after " · ".
 */
private fun resultSummary(job: JobEntity): String {
    val place = job.resultLocation?.substringBefore(": ")?.substringBefore(" · ") ?: return "Done"
    return if (job.saved()) "Saved to $place" else place
}

/** A small thumbnail of the job's clip, at the gallery's thumbnail quality, from kept thumbnails (or the camera, when it's on). */
@Composable
private fun JobThumbnail(job: JobEntity, vm: AppViewModel) {
    val online = vm.cameraState.collectAsState().value is com.openpro.camera.CameraState.Online
    val quality = vm.storage.thumbQuality.collectAsState().value
    val context = androidx.compose.ui.platform.LocalContext.current
    val media = remember(job.id) { job.toMedia() }
    val box = Modifier.padding(end = 12.dp).width(64.dp).height(36.dp)
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    // Thumbnails turned off (Storage): an empty box keeps the rows lined up.
    if (quality == null) {
        Box(box)
        return
    }
    coil3.compose.AsyncImage(
        model = remember(job.id, online, quality) {
            coil3.request.ImageRequest.Builder(context)
                .data(com.openpro.media.CameraThumb(media, online, quality))
                .size(quality.width, quality.height)
                .build()
        },
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = box,
    )
}

/** Whether the job has run at all, so has a processing time to show. */
private fun JobEntity.hasRun() = runMs > 0 || state == JobState.Running

/**
 * How long the job has spent processing: ticking only while it runs (time queued or waiting for a
 * network doesn't count), and its final time once it's done, failed or cancelled.
 */
@Composable
private fun JobTimer(job: JobEntity) {
    if (!job.hasRun()) return
    val running = job.state == JobState.Running && job.runStartedAt > 0
    val now by produceState(System.currentTimeMillis(), job.id, running, job.runStartedAt) {
        while (running) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val seconds = ((job.runMs + if (running) (now - job.runStartedAt).coerceAtLeast(0) else 0) / 1000)
    val finished = job.finishedAt > 0
    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Timer,
            contentDescription = if (finished) "Took" else "Running for",
            modifier = Modifier.size(14.dp),
            tint = if (finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            formatDuration(seconds),
            style = MaterialTheme.typography.labelSmall,
            color = if (finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun JobProgress(job: JobEntity, live: LiveProgress?) {
    // The bar follows every update; the words (amounts, speed, time left) change once a second.
    val latest by rememberUpdatedState(live)
    val words by produceState(live, job.id) {
        while (true) {
            value = latest
            kotlinx.coroutines.delay(1_000)
        }
    }
    // Internet work (uploading) in the internet colour, as in the connection bar; the rest in blue.
    val internet = live?.toInternet ?: (job.step == Step.Upload)
    val bar = if (internet) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val track = bar.copy(alpha = 0.24f)
    when {
        live != null && live.total <= 0 -> {
            Text(live.label, style = MaterialTheme.typography.bodySmall, color = bar)
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = bar, trackColor = track)
        }
        live != null -> {
            val fraction = (live.done.toFloat() / live.total).coerceIn(0f, 1f)
            val w = words?.takeIf { it.label == live.label && it.total == live.total } ?: live
            val detail = if (w.isBytes) {
                buildString {
                    append("${formatBytes(w.done)} of ${formatBytes(w.total)}")
                    if (w.bytesPerSecond > 0) {
                        append(" · ${formatBytes(w.bytesPerSecond)}/s")
                        append(" · ${formatDuration((w.total - w.done) / w.bytesPerSecond)} left")
                    }
                }
            } else {
                "${(w.done * 100 / w.total.coerceAtLeast(1)).toInt()}%"
            }
            Text("${live.label}: $detail", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = bar, trackColor = track)
        }
        job.isActive && job.bytesDone > 0 && job.bytesTotal > 0 -> {
            val fraction = (job.bytesDone.toFloat() / job.bytesTotal).coerceIn(0f, 1f)
            Text("${formatBytes(job.bytesDone)} of ${formatBytes(job.bytesTotal)} so far", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = bar, trackColor = track)
        }
    }
}

/** Retry, Cancel, or a circled cross to clear it: compact, to sit in the title's line. */
@Composable
private fun JobActions(job: JobEntity, vm: AppViewModel, scope: kotlinx.coroutines.CoroutineScope, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (job.state == JobState.Failed || job.state == JobState.Cancelled || job.state == JobState.Waiting) {
            SmallAction(if (job.state == JobState.Waiting) "Run now" else "Retry") { scope.launch { vm.batch.retry(job.id) } }
        }
        if (job.isActive) {
            SmallAction("Cancel") { scope.launch { vm.batch.cancel(job.id) } }
        } else {
            val finished = job.state == JobState.Done || job.state == JobState.Cancelled
            Icon(
                Icons.Outlined.Cancel, if (finished) "Clear" else "Remove",
                Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable { scope.launch { vm.batch.remove(job.id) } }.padding(2.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A text button no taller than the line of text it sits in. */
@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun JobDetail(vm: AppViewModel, job: JobEntity, live: LiveProgress?) {
    val log by remember(job.id) { vm.batch.log(job.id) }.collectAsState(initial = emptyList())
    val time = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(job.name, style = MaterialTheme.typography.titleLarge)
        Text("${job.title} ${destination(vm, job)} · ${job.state.name.lowercase()} · attempt ${job.attempts + 1}", style = MaterialTheme.typography.bodyMedium)
        job.stabilization?.let { Text("Stabilization: ${it.label}", style = MaterialTheme.typography.bodySmall) }
        if (job.startedAt > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Started ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(job.startedAt))} · ", style = MaterialTheme.typography.bodySmall)
                JobTimer(job)
            }
        }
        JobProgress(job, live)
        // In full here: the card shows these on one line, and a finished job's place without its path.
        job.waitingReason?.takeIf { job.state == JobState.Waiting }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        failure(job)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (job.state == JobState.Done) {
            Text(
                job.resultLocation?.let { if (job.saved()) "Saved: $it" else it } ?: "Done",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        StepPipeline(job)
        Text("Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(log, key = { it.id }) { entry ->
                Text(
                    "${time.format(Date(entry.at))}  ${entry.message}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
}
