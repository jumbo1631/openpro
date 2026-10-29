package com.openpro.quik

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.openpro.BuildConfig
import com.openpro.R
import java.util.concurrent.atomic.AtomicReference

/**
 * Hosts GoPro's engine in its own process (android:process=":quik") so a native crash in the
 * engine cannot take the app down. Jobs run one at a time on a dedicated thread.
 *
 * It runs as a foreground service while jobs run, so a render survives the app going to the
 * background. Android limits how long that may last (about 6 hours a day per service type), so:
 * it is a media-processing service (its proper type, with its own allowance) where available; it
 * leaves the foreground as soon as it's idle, and stops a job that has stopped making progress, so
 * a hung engine can't use the allowance up; and when Android says time is up, or refuses the
 * foreground, it carries on as a plain bound service (kept alive by the visible app) instead of
 * being killed, and tells the app not to ask again for a while.
 */
class QuikService : Service() {
    private lateinit var engineThread: HandlerThread
    private lateinit var engineHandler: Handler
    private lateinit var runner: QuikRunner
    private val cancelRequested = AtomicReference<String?>(null)
    /** Jobs received but not finished; touched on the main thread only. */
    private var queued = 0
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var runningJob: Pair<QuikJob, Messenger?>? = null
    @Volatile private var lastProgressAt = 0L
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        engineThread = HandlerThread("quik-engine").apply { start() }
        engineHandler = Handler(engineThread.looper)
        runner = QuikRunner(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Stabilization", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onDestroy() {
        engineThread.quitSafely()
        super.onDestroy()
    }

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_RUN -> {
                    val data = msg.data
                    data.classLoader = Network::class.java.classLoader
                    val job = QuikJob.decode(data.getString(KEY_JOB)!!)
                    val network = data.getParcelable(KEY_NETWORK, Network::class.java)
                    val reply = msg.replyTo
                    queued++
                    engineHandler.post { runJob(job, network, reply) }
                }
                MSG_CANCEL -> cancelRequested.set(msg.data.getString(KEY_JOB_ID))
            }
        }
    })

    override fun onBind(intent: Intent): IBinder = incoming.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, notification("Preparing…", 0f), FOREGROUND_TYPE)
            foreground = true
        } catch (e: Exception) {
            // Typically the daily foreground allowance is used up. Keep working as a bound service.
            Log.w(TAG, "foreground refused; continuing without it", e)
            foregroundUnavailable()
        }
        // If no job turns up (the request was lost), don't sit in the foreground.
        main.postDelayed(::stopIfIdle, IDLE_STOP_MS)
        if (!watchdogRunning) {
            watchdogRunning = true
            main.postDelayed(::watchdog, WATCHDOG_MS)
        }
        return START_NOT_STICKY
    }

    /** Android's foreground time limit for this service type was reached. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground time limit reached; continuing without foreground")
        leaveForeground()
        foregroundUnavailable()
    }

    private fun foregroundUnavailable() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong(KEY_FOREGROUND_DENIED_AT, System.currentTimeMillis()).apply()
    }

    private fun leaveForeground() {
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    private fun stopIfIdle() {
        if (queued == 0) {
            leaveForeground()
            stopSelf()
        }
    }

    private var watchdogRunning = false

    /** A job with no progress for [STUCK_MS] has hung inside the engine: fail it and restart the process. */
    private fun watchdog() {
        val current = runningJob
        if (current != null && SystemClock.elapsedRealtime() - lastProgressAt > STUCK_MS) {
            val (job, reply) = current
            Log.e(TAG, "job ${job.id} made no progress for ${STUCK_MS / 1000} s; stopping the engine")
            send(reply, MSG_DONE, Bundle().apply {
                putString(KEY_RESULT, QuikJob.json.encodeToString(
                    QuikResult.serializer(),
                    QuikResult(jobId = job.id, error = "The GoPro engine stopped responding (no progress for ${STUCK_MS / 60_000} minutes)"),
                ))
            })
            leaveForeground()
            Process.killProcess(Process.myPid())
            return
        }
        main.postDelayed(::watchdog, WATCHDOG_MS)
    }

    private fun runJob(job: QuikJob, network: Network?, reply: Messenger?) {
        // The engine reads camera URLs through java.net.URLConnection, so the whole process
        // routes through the camera's network while a camera job runs.
        val connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity.bindProcessToNetwork(if (job.source.camera != null) network else null)
        lastProgressAt = SystemClock.elapsedRealtime()
        runningJob = job to reply
        val result = try {
            runner.run(
                job,
                onProgress = { progress, message ->
                    lastProgressAt = SystemClock.elapsedRealtime()
                    send(reply, MSG_PROGRESS, Bundle().apply {
                        putString(KEY_JOB_ID, job.id)
                        putFloat(KEY_PROGRESS, progress)
                        putString(KEY_MESSAGE, message)
                    })
                    if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message, progress))
                },
                cancelled = { cancelRequested.get() == job.id },
            )
        } catch (t: Throwable) {
            Log.e(TAG, "job ${job.id} failed", t)
            QuikResult(jobId = job.id, error = t.message ?: t.toString())
        } finally {
            runningJob = null
            connectivity.bindProcessToNetwork(null)
            cancelRequested.compareAndSet(job.id, null)
        }
        send(reply, MSG_DONE, Bundle().apply { putString(KEY_RESULT, QuikJob.json.encodeToString(QuikResult.serializer(), result)) })
        Handler(Looper.getMainLooper()).post {
            if (--queued == 0) stopIfIdle()
        }
    }

    private fun send(to: Messenger?, what: Int, data: Bundle) {
        to ?: return
        runCatching { to.send(Message.obtain(null, what).apply { this.data = data }) }
            .onFailure { Log.w(TAG, "client gone", it) }
    }

    private fun notification(text: String, progress: Float): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_stabilize)
            .setContentTitle("Stabilizing")
            .setContentText(text)
            .setProgress(1000, (progress * 1000).toInt(), progress <= 0f)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "QuikService"
        private const val CHANNEL = "stabilize"
        private const val NOTIFICATION_ID = 42
        private const val IDLE_STOP_MS = 15_000L
        private const val WATCHDOG_MS = 30_000L
        private const val STUCK_MS = 5 * 60_000L
        /** Shared with the app process: when Android last refused or ended the foreground. */
        const val PREFS = "quik_service"
        const val KEY_FOREGROUND_DENIED_AT = "foregroundDeniedAt"

        /** Media processing (Android 15+) has its own daily allowance; data sync before that. */
        val FOREGROUND_TYPE: Int =
            if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        const val MSG_RUN = 1
        const val MSG_CANCEL = 2
        const val MSG_PROGRESS = 10
        const val MSG_DONE = 11
        const val KEY_JOB = "job"
        const val KEY_JOB_ID = "jobId"
        const val KEY_NETWORK = "network"
        const val KEY_PROGRESS = "progress"
        const val KEY_MESSAGE = "message"
        const val KEY_RESULT = "result"
    }
}
