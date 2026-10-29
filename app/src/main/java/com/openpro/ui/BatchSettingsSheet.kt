package com.openpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openpro.batch.ConflictPolicy
import com.openpro.batch.UploadStrategy
import com.openpro.camera.wifi.CameraReachability

/** Batch settings; every non-obvious choice has an ⓘ with the full story. */
@Composable
fun BatchSettingsSheet(vm: AppViewModel) {
    val config by vm.batchConfig.collectAsState()
    val update = vm.batch.settings::update
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Batch settings", style = MaterialTheme.typography.titleLarge)

        Heading("Uploads", Explainers.uploadStrategy)
        UploadStrategy.entries.forEach { s ->
            Choice(s.label, s.summary, config.uploadStrategy == s) { update { it.copy(uploadStrategy = s) } }
        }

        Heading("If the file already exists", Explainers.conflicts)
        ConflictPolicy.entries.forEach { p ->
            Choice(p.label, p.description, config.conflictPolicy == p) { update { it.copy(conflictPolicy = p) } }
        }

        // Camera Link, mobile data and Wi-Fi switching are on the Network page: they're not only the batch's.
        Heading("More", null)
        Toggle("Turn camera Wi-Fi off when done", "Saves the camera's battery once nothing needs it.", config.turnCameraWifiOffWhenDone) { v ->
            update { it.copy(turnCameraWifiOffWhenDone = v) }
        }
        Text("Upload chunk size (Nextcloud)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        Text("Smaller chunks lose less on a dropped connection; larger ones are a little faster.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 25, 50).forEach { mb ->
                FilterChip(selected = config.chunkSizeMb == mb, onClick = { update { it.copy(chunkSizeMb = mb) } }, label = { Text("$mb MB") })
            }
        }
    }
}

/** Title and body of an explanation dialog. */
data class Explainer(val title: String, val body: String)

@Composable
internal fun Heading(text: String, explainer: Explainer?) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (explainer != null) InfoButton(explainer)
    }
}

@Composable
fun InfoButton(explainer: Explainer) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.Info, "About ${explainer.title}") }
    if (open) ExplainDialog(explainer) { open = false }
}

@Composable
fun ExplainDialog(explainer: Explainer, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text(explainer.title) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                // Paragraphs starting with "## " are subheadings.
                explainer.body.split("\n\n").forEach { para ->
                    if (para.startsWith("## ")) {
                        Text(para.removePrefix("## "), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    } else {
                        Text(para, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
        },
    )
}

@Composable
internal fun Choice(label: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun Toggle(label: String, description: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

object Explainers {
    val stabilizeFrom = Explainer(
        "Stabilize from",
        "Every original is the same file, so the result is the same whichever you pick. It only changes where " +
            "it's read from: a copy on the phone is quickest, the camera streams it over its Wi-Fi, and a server's " +
            "copy is downloaded first.",
    )

    val uploadStrategy = Explainer(
        "Upload per item or as a batch",
        "Most phones can't be on the camera's Wi-Fi and the internet at the same time, so OpenPro switches " +
            "between them. This decides how often.\n\n" +
            "## Upload per item\n\n" +
            "Each clip is downloaded, uploaded and checked before the next one starts.\n" +
            "+ Needs space for only one clip, and clips reach the server one by one.\n" +
            "− Switches Wi-Fi twice per clip, 10 to 30 seconds each time.\n\n" +
            "## Upload as a batch\n\n" +
            "Every clip is downloaded first, then they're all uploaded.\n" +
            "+ Switches once, so it's much faster for many clips, and the camera can go off once downloads finish.\n" +
            "− Needs space for all the clips at once (if it runs out, OpenPro uploads what it has first).",
    )

    val conflicts = Explainer(
        "If the file already exists",
        "## Skip if identical (default)\n\n" +
            "A file with the same name and size (and checksum, where the server reports one) counts as already done, so a " +
            "batch can be re-run safely. A different file with that name is kept, and the new one gets a numbered name.\n\n" +
            "## Always overwrite\n\n" +
            "Whatever is at the destination is replaced.\n\n" +
            "Applies to the phone's gallery and to your server.",
    )

    @Composable
    fun cameraConnection(vm: AppViewModel): Explainer {
        val context = LocalContext.current
        val installed = vm.camera.link.installed
        val vpn = CameraReachability.vpnActive(context)
        return Explainer(
            "Camera connection and VPNs",
            "Without a VPN, none of this applies: OpenPro connects to the camera by itself and you don't need anything else.\n\n" +
                "A VPN that covers OpenPro can stop it from using any other network, including the camera's Wi-Fi. Android " +
                "enforces this for each app, and no app can work around it for itself. If you can exclude OpenPro from " +
                "your VPN, that's the simplest fix. If you need the VPN for OpenPro (e.g. to reach your server), use Camera Link.\n\n" +
                "## Camera Link\n\n" +
                "A separate small app that only joins the camera's Wi-Fi and passes the camera's data to OpenPro inside the " +
                "phone (this internal connection never goes through a VPN). Exclude Camera Link from your VPN and keep " +
                "OpenPro on it, so OpenPro can reach the camera and your server at the same time.\n\n" +
                "## Set up\n\n" +
                "1. Install Camera Link, built alongside OpenPro (see the README).\n" +
                "2. In your VPN app, add \"Camera Link\" to the apps that bypass the VPN (split tunnelling, excluded apps).\n\n" +
                "That's all. OpenPro always tries the camera by itself first, and uses Camera Link only when the VPN refuses " +
                "it (then straight away for as long as that VPN stays on).\n\n" +
                "## Right now\n\n" +
                "Camera Link: ${if (installed) "installed" else "not installed"}. VPN: ${if (vpn) "active" else "not active"}.",
        )
    }

    val network = Explainer(
        "Which network is the phone on?",
        "This chip shows how OpenPro is connected right now.\n\n" +
            "## Camera\n\n" +
            "The phone is on the camera's own Wi-Fi. Browsing, previews and downloads work; there is no internet through it.\n\n" +
            "## Internet\n\n" +
            "The phone's usual connection (Wi-Fi or mobile data, through your VPN if one is on). Uploads to your server use this.\n\n" +
            "## Both\n\n" +
            "Some phones can be on the camera's Wi-Fi and the internet at the same time, and mobile data can provide internet " +
            "while on the camera's Wi-Fi. Then nothing needs to switch.\n\n" +
            "## Switching\n\n" +
            "The batch is moving the phone between the camera's Wi-Fi and the internet. This takes 10 to 30 seconds.\n\n" +
            "\"via Camera Link\" means Camera Link holds the camera connection because a VPN covers OpenPro.",
    )
}
