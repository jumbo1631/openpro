package com.openpro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openpro.quik.JobKind
import com.openpro.quik.JobSource
import com.openpro.quik.QuikJob
import com.openpro.quik.StabilizationChoice
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Debug builds only: runs an engine job on a local file from adb, without the camera.
 *
 *   adb shell am broadcast -n com.openpro/.DebugEngineReceiver \
 *       --es path /sdcard/Android/data/com.openpro/files/test.mp4 --es stab Suggested --es kind Preview
 *
 * Results are logged under the "DebugEngine" tag.
 */
class DebugEngineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: run {
            Log.e(TAG, "missing --es path")
            return
        }
        val stab = StabilizationChoice.valueOf(intent.getStringExtra("stab") ?: "Suggested")
        val kind = JobKind.valueOf(intent.getStringExtra("kind") ?: "Preview")
        val out = File(context.getExternalFilesDir(null), "debug_out_${kind}_$stab.mp4")
        val job = QuikJob(
            id = UUID.randomUUID().toString(),
            kind = kind,
            source = JobSource(localPath = path),
            stabilization = stab,
            outputPath = out.path,
        )
        val pending = goAsync()
        context.app.scope.launch {
            Log.i(TAG, "running $job")
            val started = System.currentTimeMillis()
            val result = context.app.quik.run(job, null)
            Log.i(TAG, "result after ${System.currentTimeMillis() - started} ms: $result (output ${out.length()} bytes)")
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "DebugEngine"
    }
}
