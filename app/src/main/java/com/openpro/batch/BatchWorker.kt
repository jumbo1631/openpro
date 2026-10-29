package com.openpro.batch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.openpro.OpenProApp
import com.openpro.MainActivity
import com.openpro.R
import com.openpro.camera.CameraState
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Runs the batch queue in the background, as a foreground job so Android keeps it alive during
 * long transfers. Loops until nothing is runnable, then schedules a wake-up for the next retry.
 */
class BatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext as OpenProApp

    override suspend fun doWork(): Result {
        try {
            setForeground(foregroundInfo("Starting…"))
        } catch (e: Exception) {
            // Started from the background (e.g. after a reboot) where Android forbids a foreground
            // service: run anyway; anything unfinished resumes on the next run.
            Log.w(TAG, "running without foreground", e)
        }
        val runner = app.batchRunner
        val startedAt = System.currentTimeMillis()
        val before = app.batch.db.dao().pending().size
        while (!isStopped) {
            val jobs = app.batch.jobs.first()
            val running = jobs.firstOrNull { it.state == JobState.Running || it.state == JobState.Queued }
            // Renders count as media processing (Android 15+), which has its own daily allowance.
            val rendering = running?.step == Step.Render
            runCatching { setForeground(foregroundInfo(summary(jobs, running), rendering)) }
            when (val pass = runner.runOnePass()) {
                BatchRunner.Pass.Ran -> Unit
                is BatchRunner.Pass.Idle -> {
                    val safety = if (pass.waiting > 0) System.currentTimeMillis() + SAFETY_WAKE_MS else null
                    listOfNotNull(pass.wakeAt, safety).minOrNull()?.let { BatchScheduler.wakeAt(applicationContext, it) }
                    break
                }
            }
        }
        val after = app.batch.jobs.first()
        val done = after.count { it.state == JobState.Done && it.updatedAt >= startedAt }
        val failed = after.count { it.state == JobState.Failed && it.updatedAt >= startedAt }
        maybeTurnCameraWifiOff(after)
        if (before > 0 && after.none { it.isActive }) notifyFinished(done, failed)
        return Result.success()
    }

    private suspend fun maybeTurnCameraWifiOff(jobs: List<JobEntity>) {
        if (!app.batch.settings.config.value.turnCameraWifiOffWhenDone) return
        if (app.camera.state.value !is CameraState.Online) return
        if (jobs.any { it.isActive && app.batchRunner.needsCamera(it) }) return
        runCatching { app.camera.goOffline() }
    }

    private fun summary(jobs: List<JobEntity>, current: JobEntity?): String {
        val active = jobs.count { it.isActive }
        val live = current?.let { app.batch.live.value[it.id] }
        val head = current?.let { "${it.title}: ${it.name}" } ?: "Batch"
        val progress = live?.let { if (it.total > 0) " · ${it.done * 100 / it.total}%" else "" } ?: ""
        return "$head$progress · $active left"
    }

    private fun foregroundInfo(text: String, rendering: Boolean = false): ForegroundInfo {
        ensureChannel(applicationContext)
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_stabilize)
            .setContentTitle("OpenPro batch")
            .setContentText(text)
            .setContentIntent(openBatchPage(applicationContext))
            .setOngoing(true)
            .build()
        val type = if (rendering && android.os.Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(NOTIFICATION_ID, notification, type)
    }

    private fun notifyFinished(done: Int, failed: Int) {
        val text = if (failed == 0) "$done finished" else "$done finished, $failed failed"
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_stabilize)
            .setContentTitle("Batch complete")
            .setContentText(text)
            .setContentIntent(openBatchPage(applicationContext))
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(SUMMARY_ID, notification)
    }

    companion object {
        private const val TAG = "BatchWorker"
        private const val CHANNEL = "batch"
        private const val NOTIFICATION_ID = 77
        private const val SUMMARY_ID = 78
        /** Re-check waiting jobs at least this often even without a trigger. */
        private const val SAFETY_WAKE_MS = 15 * 60_000L
        const val EXTRA_OPEN_BATCH = "openBatch"

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Batch transfers", NotificationManager.IMPORTANCE_LOW))
        }

        fun openBatchPage(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_BATCH, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Wakes the delayed retry check, which just kicks the main worker. */
class BatchWakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        BatchScheduler.kick(applicationContext)
        return Result.success()
    }
}

object BatchScheduler {
    private const val RUN = "batch-run"
    private const val WAKE = "batch-wake"

    /**
     * Makes sure the queue gets processed. If a run is in progress, another is appended after it
     * so jobs added during its last moments are never stranded.
     */
    fun kick(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            RUN, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<BatchWorker>().build(),
        )
    }

    fun wakeAt(context: Context, at: Long) {
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(1_000)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WAKE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BatchWakeWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
        )
    }
}

/** After a reboot, resume any unfinished batch. */
class BatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) BatchScheduler.kick(context)
    }
}
