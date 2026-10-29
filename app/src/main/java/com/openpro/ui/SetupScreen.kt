package com.openpro.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openpro.quik.EngineFiles

/** Whether the first-launch stabilization page has been shown (and dealt with). */
object FirstRun {
    private fun prefs(context: Context) = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
    private const val STABILIZATION_OFFERED = "stabilization_offered"

    /** True the first time OpenPro opens, unless the engine is already there. */
    fun offerStabilization(context: Context) =
        !prefs(context).getBoolean(STABILIZATION_OFFERED, false) && !EngineFiles.available(context)

    fun stabilizationOffered(context: Context) {
        prefs(context).edit().putBoolean(STABILIZATION_OFFERED, true).apply()
    }
}

/**
 * Shown when OpenPro first opens: adding stabilization, with the same choices as About's, or
 * leaving it for later (and where to find it then). [onDone] either way.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    var later by remember { mutableStateOf(false) }
    EngineSetup(onAdded = onDone) { sources, _ ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Videocam, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text("Add stabilization", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "OpenPro can stabilize your clips with GoPro's own engine, the same one the Quik app uses. " +
                        "It isn't included with OpenPro, so it's taken from Quik ${EngineFiles.QUIK_VERSION}. Pick a way:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                Spacer(Modifier.height(8.dp))
                StabilizationOptions(sources, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { later = true }) { Text("I'll do it later") }
            }
        }
    }
    if (later) {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Add it any time") },
            text = { Text("On the home screen, tap About, then Add stabilization.") },
            confirmButton = { TextButton(onClick = onDone) { Text("OK") } },
        )
    }
}
