package com.openpro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps the app process alive (foreground) while originals download from the camera. */
class TransferService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_LOW))
        try {
            startForeground(ID, notification("Starting download…", 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Daily foreground allowance used up: the download continues while OpenPro is open.
            android.util.Log.w("TransferService", "foreground refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            watcher = app.scope.launch {
                app.downloader.transfers.collect { transfers ->
                    val active = transfers.values.filter { it.error == null }
                    if (active.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val bytes = active.sumOf { it.bytes }
                        val total = active.sumOf { it.total }.coerceAtLeast(1)
                        nm.notify(ID, notification("Downloading ${active.size} file(s)", bytes.toFloat() / total))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android's daily foreground time limit was reached: stop cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }

    private fun notification(text: String, progress: Float): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_stabilize)
            .setContentTitle("OpenPro")
            .setContentText(text)
            .setProgress(1000, (progress * 1000).toInt(), progress <= 0f)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL = "transfers"
        private const val ID = 7

        fun start(context: Context) = context.startForegroundService(Intent(context, TransferService::class.java))
    }
}
