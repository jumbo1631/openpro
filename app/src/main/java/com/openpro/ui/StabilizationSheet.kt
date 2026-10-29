package com.openpro.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.openpro.quik.EngineFiles
import com.openpro.quik.EngineFiles.InstalledQuik
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** The ways to add GoPro's engine, for [StabilizationOptions] to offer. */
class EngineSources(val usePhone: () -> Unit, val download: () -> Unit, val pick: () -> Unit)

/**
 * Adding GoPro's engine, whichever way the user picks from [content]: GoPro Quik on this phone,
 * Quik downloaded from APKMirror (on its page inside OpenPro, so the download goes straight in),
 * or a Quik file already on the phone. Shows the progress and how it went. [content] is told when
 * it's [busy] (one of those is under way), and [onAdded] is called once the engine is in.
 */
@Composable
fun EngineSetup(onAdded: () -> Unit, content: @Composable (sources: EngineSources, busy: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var browsing by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf<String?>(null) }
    var cancel by remember { mutableStateOf<AtomicBoolean?>(null) }
    var result by remember { mutableStateOf<Result<Unit>?>(null) }

    /** Only a download can be cancelled; the rest takes seconds. */
    fun run(cancellable: Boolean = false, install: (progress: (String) -> Unit, cancelled: () -> Boolean) -> Unit) {
        val cancelled = AtomicBoolean(false)
        cancel = cancelled.takeIf { cancellable }
        step = "Starting…"
        scope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { install({ s -> scope.launch { step = s } }, cancelled::get) } }
            step = null
            cancel = null
            // Cancelled: back to the choices, nothing to report.
            if (!cancelled.get()) result = outcome
        }
    }

    val pick = rememberLauncherForActivityResult(OpenInDownloads()) { uri ->
        if (uri != null) run { progress, _ -> EngineFiles.install(context, uri, progress) }
    }
    val sources = remember {
        EngineSources(
            usePhone = { run { progress, _ -> EngineFiles.installFromPhone(context, progress) } },
            download = { browsing = true },
            pick = { pick.launch(arrayOf("*/*")) },
        )
    }

    content(sources, browsing || step != null || result != null)

    if (browsing) {
        ApkMirrorPage(
            onDownload = { url, headers ->
                browsing = false
                run(cancellable = true) { progress, cancelled -> EngineFiles.installDownload(context, url, headers, progress, cancelled) }
            },
            onClose = { browsing = false },
        )
    }
    step?.let {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Adding stabilization") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 16.dp))
                    Text(it)
                }
            },
            confirmButton = {},
            dismissButton = { cancel?.let { c -> TextButton(onClick = { c.set(true) }) { Text("Cancel") } } },
        )
    }
    result?.let { r ->
        val ok = r.isSuccess
        AlertDialog(
            onDismissRequest = { result = null; if (ok) onAdded() },
            title = { Text(if (ok) "Stabilization is ready" else "Couldn't add stabilization") },
            text = {
                Text(
                    if (ok) "The stabilize options are now available on every clip."
                    else r.exceptionOrNull()?.message ?: "Something went wrong. Try again, or another way.",
                )
            },
            confirmButton = { TextButton(onClick = { result = null; if (ok) onAdded() }) { Text("OK") } },
        )
    }
}

/**
 * The three ways to add the engine, as cards. Using Quik on this phone works only when it's the
 * right version; otherwise that card says why and can't be tapped.
 */
@Composable
fun StabilizationOptions(sources: EngineSources, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var quik by remember { mutableStateOf(EngineFiles.installedQuik(context)) }
    // Quik may have been installed or updated while OpenPro was in the background.
    LifecycleResumeEffect(Unit) {
        quik = EngineFiles.installedQuik(context)
        onPauseOrDispose {}
    }
    Column(modifier) {
        Option(
            Icons.Filled.PhoneAndroid,
            "Use GoPro Quik on this phone",
            when (val q = quik) {
                InstalledQuik.Usable -> "Quik ${EngineFiles.QUIK_VERSION} is installed. Takes a few seconds."
                is InstalledQuik.WrongVersion -> "Quik ${q.version} is installed, which won't work: it has to be ${EngineFiles.QUIK_VERSION}."
                InstalledQuik.None -> "GoPro Quik isn't installed."
            },
            enabled = quik == InstalledQuik.Usable,
            onClick = sources.usePhone,
        )
        Option(
            Icons.Filled.Download,
            "Download from APKMirror",
            "Opens Quik ${EngineFiles.QUIK_VERSION} on APKMirror. Scroll part way down and tap the black \"DOWNLOAD APK BUNDLE\" button.",
            onClick = sources.download,
        )
        Option(
            Icons.Filled.FolderOpen,
            "Pick the file",
            "An .apkm, .xapk, .apks or APK of Quik ${EngineFiles.QUIK_VERSION} already on this phone.",
            onClick = sources.pick,
        )
    }
}

/** About's "Add stabilization": the options in a bottom sheet. [onClose] once added or dismissed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStabilization(onClose: () -> Unit) {
    EngineSetup(onAdded = onClose) { sources, busy ->
        if (!busy) {
            ModalBottomSheet(onDismissRequest = onClose) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    Text("Add stabilization", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
                    Text(
                        "OpenPro uses GoPro's own stabilization engine, taken from the GoPro Quik app. " +
                            "It has to be Quik ${EngineFiles.QUIK_VERSION} exactly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 8.dp),
                    )
                    StabilizationOptions(sources)
                }
            }
        }
    }
}

@Composable
private fun Option(
    icon: ImageVector, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        // A step above the sheet's own background (surfaceContainerLow), so each option reads as a card.
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
    )
}

/**
 * Quik's download page on APKMirror, inside OpenPro: when the user taps its download button, the
 * download comes to [onDownload] (with the page's cookies, so the site lets it through) instead
 * of the browser's downloads. Only APKMirror's own pages open, so its ads go nowhere.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ApkMirrorPage(onDownload: (url: String, headers: Map<String, String>) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
                    Text("GoPro Quik ${EngineFiles.QUIK_VERSION}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web?.url ?: EngineFiles.APKMIRROR_URL)))
                    }) { Icon(Icons.Filled.OpenInBrowser, "Open in your browser") }
                }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Scroll part way down and tap the black \"DOWNLOAD APK BUNDLE\" button. OpenPro takes it from there.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                if (loading in 1..99) LinearProgressIndicator(progress = { loading / 100f }, modifier = Modifier.fillMaxWidth())
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    // The site's own redirects (its download goes through its file servers) are fine.
                                    if (request.isRedirect) return false
                                    val host = request.url.host.orEmpty()
                                    return !(host == "apkmirror.com" || host.endsWith(".apkmirror.com"))
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    loading = newProgress
                                }
                            }
                            setDownloadListener { url, userAgent, _, _, _ ->
                                val headers = buildMap {
                                    put("User-Agent", userAgent)
                                    CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
                                    this@apply.url?.let { put("Referer", it) }
                                }
                                onDownload(url, headers)
                            }
                            loadUrl(EngineFiles.APKMIRROR_URL)
                            web = this
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
        BackHandler {
            val w = web
            if (w != null && w.canGoBack()) w.goBack() else onClose()
        }
    }
}

/** The file picker, opening in Downloads (where a browser download lands). */
private class OpenInDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input).putExtra(
        DocumentsContract.EXTRA_INITIAL_URI,
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"),
    )
}
