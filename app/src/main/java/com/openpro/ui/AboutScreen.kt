package com.openpro.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openpro.BuildConfig

/** Where OpenPro lives on GitHub: the About page's links all go here. */
private const val REPO = "https://github.com/jumbo1631/openpro"

/** The app's version, and links for feedback, bugs, feature requests and the source. */
@Composable
fun AboutScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val open: (String) -> Unit = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val engine = if (vm.quik.engineAvailable) "included" else "not included"

    Screen("About", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("OpenPro", style = MaterialTheme.typography.headlineSmall)
                Text("Version $version", style = MaterialTheme.typography.bodyLarge)
                EngineBadge()
                Text(
                    "An unofficial app, not made or endorsed by GoPro.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Link(Icons.Filled.Star, "Star OpenPro on GitHub", "If you like it, a star helps others find it") { open(REPO) }
            Link(Icons.Filled.BugReport, "Report a bug", "Opens a new GitHub issue with your app and phone details filled in") {
                open(issue("bug", bugReport(version, engine)))
            }
            Link(Icons.Filled.Lightbulb, "Request a feature", "Opens a new GitHub issue") { open(issue("enhancement", "")) }
            Link(Icons.Filled.Forum, "Send feedback", "Questions, ideas and experiences, in GitHub Discussions") { open("$REPO/discussions") }
            Link(Icons.Filled.Code, "Source code", "github.com/jumbo1631/openpro") { open(REPO) }
        }
    }
}

/** Whether GoPro's stabilization engine has been added: a green tick when it has, and a button to add it when not. */
@Composable
private fun EngineBadge() {
    val context = LocalContext.current
    var included by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.openpro.quik.EngineFiles.available(context)) }
    var adding by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // The engine is copied out of GoPro Quik the user supplies: nothing of GoPro's ships with OpenPro.
    if (adding) {
        AddStabilization(onClose = {
            adding = false
            included = com.openpro.quik.EngineFiles.available(context)
        })
    }
    val color = if (included) androidx.compose.ui.graphics.Color(0xFF43A047) else MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.material3.Surface(
        Modifier.padding(vertical = 8.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.14f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f)),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (included) Icons.Filled.Verified else Icons.Filled.VideocamOff,
                null, Modifier.padding(2.dp), tint = color,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    if (included) "Stabilization ready" else "No stabilization yet",
                    style = MaterialTheme.typography.titleSmall, color = color,
                )
                Text(
                    if (included) "GoPro's engine, added from your copy of GoPro Quik."
                    else "Add GoPro's engine, from the GoPro Quik app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!included) {
                    androidx.compose.material3.Button(onClick = { adding = true }, Modifier.padding(top = 8.dp)) {
                        Text("Add stabilization")
                    }
                } else {
                    androidx.compose.material3.TextButton(onClick = {
                        com.openpro.quik.EngineFiles.remove(context)
                        included = com.openpro.quik.EngineFiles.available(context)
                    }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun Link(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Icon(Icons.Filled.ArrowOutward, "Opens in your browser", tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun issue(label: String, body: String) =
    "$REPO/issues/new?labels=$label" + if (body.isEmpty()) "" else "&body=" + Uri.encode(body)

/** A bug report's starting text: what happened, plus the details that help track it down. */
private fun bugReport(version: String, engine: String) = """
    **What happened**


    **What you expected**


    **Steps to reproduce**
    1.

    **Camera model and firmware**


    ---
    OpenPro $version, stabilization engine $engine
    ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
""".trimIndent()
