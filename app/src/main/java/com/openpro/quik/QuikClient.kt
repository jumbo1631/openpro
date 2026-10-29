package com.openpro.quik

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class JobProgress(val jobId: String, val progress: Float, val message: String)

/** UI-process side of [QuikService]: submits jobs and exposes progress. */
class QuikClient(private val context: Context) {
    private var service: Messenger? = null
    private var bound: CompletableDeferred<Messenger>? = null
    private val waiting = mutableMapOf<String, CompletableDeferred<QuikResult>>()
    private val _progress = MutableStateFlow<Map<String, JobProgress>>(emptyMap())
    val progress: StateFlow<Map<String, JobProgress>> = _progress

    /** GoPro's engine has been added on the phone (About › Add stabilization, see [EngineFiles]). */
    val engineAvailable: Boolean get() = EngineFiles.available(context)

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                QuikService.MSG_PROGRESS -> {
                    val id = msg.data.getString(QuikService.KEY_JOB_ID) ?: return
                    val p = JobProgress(id, msg.data.getFloat(QuikService.KEY_PROGRESS), msg.data.getString(QuikService.KEY_MESSAGE).orEmpty())
                    _progress.update { it + (id to p) }
                }
                QuikService.MSG_DONE -> {
                    val result = QuikJob.json.decodeFromString(QuikResult.serializer(), msg.data.getString(QuikService.KEY_RESULT)!!)
                    _progress.update { it - result.jobId }
                    waiting.remove(result.jobId)?.complete(result)
                }
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val messenger = Messenger(binder)
            service = messenger
            bound?.complete(messenger)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // The engine process died (likely a native crash): fail everything in flight.
            service = null
            bound = null
            val failed = waiting.toMap()
            waiting.clear()
            _progress.value = emptyMap()
            failed.forEach { (id, d) -> d.complete(QuikResult(jobId = id, error = "The GoPro engine crashed")) }
        }
    }

    private suspend fun messenger(): Messenger {
        service?.let { return it }
        val pending = bound ?: CompletableDeferred<Messenger>().also {
            bound = it
            context.bindService(Intent(context, QuikService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        return pending.await()
    }

    /** Runs a job and suspends until it finishes. Must be called from the main thread. */
    suspend fun run(job: QuikJob, network: Network?): QuikResult {
        val target = messenger()
        val done = CompletableDeferred<QuikResult>()
        waiting[job.id] = done
        _progress.update { it + (job.id to JobProgress(job.id, 0f, "Queued")) }
        // Started as well as bound, so it can stay in the foreground while the UI is gone. If Android
        // refused or ended that recently (daily allowance used up), the binding alone keeps it
        // alive while OpenPro is open or its batch is running.
        val deniedAt = context.getSharedPreferences(QuikService.PREFS, Context.MODE_PRIVATE)
            .getLong(QuikService.KEY_FOREGROUND_DENIED_AT, 0)
        if (System.currentTimeMillis() - deniedAt > 24 * 3_600_000L) {
            runCatching { context.startForegroundService(Intent(context, QuikService::class.java)) }
                .onFailure { Log.w(TAG, "could not start the engine in the foreground", it) }
        }
        target.send(Message.obtain(null, QuikService.MSG_RUN).apply {
            replyTo = replies
            data = Bundle().apply {
                putString(QuikService.KEY_JOB, job.encode())
                network?.let { putParcelable(QuikService.KEY_NETWORK, it) }
            }
        })
        return done.await()
    }

    fun cancel(jobId: String) {
        service?.send(Message.obtain(null, QuikService.MSG_CANCEL).apply {
            data = Bundle().apply { putString(QuikService.KEY_JOB_ID, jobId) }
        })
    }

    companion object {
        private const val TAG = "QuikClient"
    }
}
