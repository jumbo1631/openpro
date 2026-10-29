package com.openpro.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openpro.app
import com.openpro.batch.BatchErrors
import com.openpro.batch.HttpStatusException
import com.openpro.server.NextcloudAppAccount
import com.openpro.server.NextcloudLogin
import com.openpro.server.ServerClient
import com.openpro.server.ServerProfile
import com.openpro.server.ServerType
import com.openpro.server.TrustPolicy
import com.openpro.server.UntrustedCertificateException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Your servers: which one uploads go to, test, edit, remove. */
@Composable
fun ServersScreen(vm: AppViewModel, onEdit: (String?) -> Unit, onBack: () -> Unit) {
    val servers by vm.servers.servers.collectAsState()
    val defaultId by vm.servers.defaultId.collectAsState()
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var confirmRemove by remember { mutableStateOf<ServerProfile?>(null) }

    Screen("Servers", onBack = onBack, actions = {
        androidx.compose.material3.IconButton(onClick = { onEdit(null) }) { Icon(Icons.Filled.Add, "Add server") }
    }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(ServerIntro.body, style = MaterialTheme.typography.bodyMedium)
            if (servers.isEmpty()) {
                Button(onClick = { onEdit(null) }, Modifier.fillMaxWidth()) { Text("Set up a server") }
            }
            servers.sortedBy { it.name.lowercase() }.forEach { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = defaultId == s.id || servers.size == 1, onClick = { vm.servers.setDefault(s.id) })
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${s.type.label} · ${s.baseUrl}", style = MaterialTheme.typography.bodySmall)
                                    if (s.isCleartext) {
                                        Icon(
                                            Icons.Filled.LockOpen, "Unencrypted (http://)",
                                            Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                Text("Uploads to ${listOf(s.folder.trimEnd('/'), s.layout).filter { it.isNotEmpty() }.joinToString("/")}/ as ${s.username}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (s.pinnedCertSha256 != null) Text("Trusts a self-signed certificate (pinned)", style = MaterialTheme.typography.bodySmall)
                        // No saved password (e.g. the server's sign-in was lost): uploads wait until signed in.
                        if (vm.servers.password(s.id) == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Not signed in. Uploads to it wait until you are.",
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                )
                                Button(onClick = { onEdit(s.id) }) { Text("Sign in") }
                            }
                        }
                        results[s.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                results = results + (s.id to "Testing…")
                                scope.launch {
                                    val r = try {
                                        vm.servers.client(s).test()
                                    } catch (e: Exception) {
                                        "Failed: " + describeServerError(e)
                                    }
                                    results = results + (s.id to r)
                                }
                            }) { Text("Test") }
                            // Finds files deleted on the server by hand, so OpenPro stops listing them as uploaded.
                            TextButton(onClick = {
                                results = results + (s.id to "Checking the files OpenPro uploaded…")
                                scope.launch { results = results + (s.id to vm.checkUploads(s)) }
                            }) { Text("Check files") }
                            TextButton(onClick = { onEdit(s.id) }) { Text("Edit") }
                            TextButton(onClick = { confirmRemove = s }) { Text("Remove") }
                        }
                    }
                }
            }
            if (servers.size > 1) {
                Text(
                    "Uploads go to the selected server. Changing it moves nothing already uploaded, and uploads already in " +
                        "the batch keep the server they were queued with.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    confirmRemove?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${s.name}?") },
            text = { Text("OpenPro forgets this server and its saved password. Files already uploaded stay on the server; queued uploads to it will wait.") },
            confirmButton = { TextButton(onClick = { vm.servers.remove(s.id); confirmRemove = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

object ServerIntro {
    const val title = "Your own server"
    const val body =
        "Upload to a server of your own:\n" +
            "• Nextcloud (recommended)\n" +
            "• Any WebDAV server, such as ownCloud or most NAS boxes"
    const val cleartextWarning =
        "This server uses http://, so files and your password travel unencrypted. That's fine through a VPN " +
            "(e.g. WireGuard or Tailscale) or on a network you trust; otherwise use https://."
}

fun describeServerError(e: Throwable): String = when {
    e is HttpStatusException && e.code == 401 -> "Sign-in rejected: check the user name and password (for Nextcloud, an app password)."
    e is HttpStatusException && e.code == 404 -> "Not found: check the address. For WebDAV, use the full WebDAV URL."
    e is HttpStatusException && e.code == 405 -> "The server doesn't accept WebDAV here: check the address."
    else -> BatchErrors.describe(e)
}

/** https:// or http:// for the server's address, so neither has to be typed. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SchemeSwitch(secure: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(true to "https://", false to "http://").forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = secure == value,
                onClick = { onChange(value) },
                shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, 2),
                icon = {
                    androidx.compose.material3.SegmentedButtonDefaults.Icon(secure == value) {
                        Icon(if (value) Icons.Filled.Lock else Icons.Filled.LockOpen, null, Modifier.size(18.dp))
                    }
                },
            ) { Text(label) }
        }
    }
}

/**
 * Signing in to Nextcloud, with nothing more on screen than the chosen way needs: the Nextcloud
 * app first (if it's installed: no address to type, it knows it), then the browser (which needs
 * the address), and an app password as the last resort. Once signed in, just who and where.
 */
@Composable
private fun NextcloudSignIn(model: ServerSetupModel, form: ServerSetupModel.Form) {
    val context = LocalContext.current
    val openBrowser: (String) -> Unit = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    val appInstalled = remember { NextcloudAppAccount.available(context) }
    val pickAccount = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val name = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
        val type = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_TYPE)
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        if (name != null && type != null && activity != null) model.signInWithNextcloudApp(activity, name, type, openBrowser)
    }
    val idle = form.busy == null
    when {
        form.signedIn -> {
            SuccessLine("Signed in as ${form.username} on ${form.url}")
            Text(
                if (form.viaApp) "Through the Nextcloud app" else "With an app password you can revoke in Nextcloud",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                model.update { it.copy(signedIn = false, viaApp = false, manualPassword = false, username = "", password = "") }
            }) { Text("Sign in again") }
        }
        form.manualPassword -> {
            AddressField(model, form)
            Credentials(model, form)
            TextButton(onClick = { model.update { it.copy(manualPassword = false) } }) { Text("Sign in instead") }
        }
        else -> {
            if (appInstalled) {
                Button(onClick = { pickAccount.launch(NextcloudAppAccount.chooser()) }, Modifier.fillMaxWidth(), enabled = idle) {
                    Text("Sign in with the Nextcloud app")
                }
                Text("Pick your account. Nothing to type.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OrDivider()
            }
            AddressField(model, form)
            val browser: @Composable () -> Unit = { Text("Sign in with browser") }
            if (appInstalled) {
                OutlinedButton(onClick = { model.signInWithBrowser(openBrowser) }, Modifier.fillMaxWidth(), enabled = idle && form.url.isNotBlank()) { browser() }
            } else {
                Button(onClick = { model.signInWithBrowser(openBrowser) }, Modifier.fillMaxWidth(), enabled = idle && form.url.isNotBlank()) { browser() }
            }
            TextButton(onClick = { model.update { it.copy(manualPassword = true) } }) { Text("Use an app password instead") }
        }
    }
}

/** The server's address, with https:// or http:// picked by a switch, and the warning that goes with http://. */
@Composable
private fun AddressField(model: ServerSetupModel, form: ServerSetupModel.Form) {
    SchemeSwitch(form.secure) { secure -> model.update { it.copy(secure = secure) } }
    OutlinedTextField(
        form.url, { v -> model.setAddress(v) },
        label = { Text(if (form.type == ServerType.Nextcloud) "Nextcloud address" else "WebDAV address") },
        placeholder = { Text(if (form.type == ServerType.Nextcloud) "cloud.example.com" else "nas.example.com/webdav") },
        prefix = { Text(if (form.secure) "https://" else "http://") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    if (!form.secure) {
        Text(ServerIntro.cleartextWarning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** User name and password: a WebDAV server's, or a Nextcloud app password. */
@Composable
private fun Credentials(model: ServerSetupModel, form: ServerSetupModel.Form) {
    OutlinedTextField(
        form.username, { v -> model.update { it.copy(username = v) } },
        label = { Text("User name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        form.password, { v -> model.update { it.copy(password = v) } },
        label = { Text(if (form.type == ServerType.Nextcloud) "App password" else "Password") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        supportingText = if (form.type == ServerType.Nextcloud) {
            { Text("Make one in Nextcloud: Settings › Security › Devices & sessions") }
        } else {
            null
        },
    )
}

/** A thin line with "or" in the middle, between two ways of doing the same thing. */
@Composable
private fun OrDivider() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.HorizontalDivider(Modifier.weight(1f))
        Text("or", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.HorizontalDivider(Modifier.weight(1f))
    }
}

/** A green tick and a message: signed in, or the connection test passed. */
@Composable
private fun SuccessLine(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.CheckCircle, null, tint = SuccessGreen)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = SuccessGreen)
    }
}

private val SuccessGreen = androidx.compose.ui.graphics.Color(0xFF66BB6A)

/** Holds the add/edit form so it survives rotation, and runs sign-in and testing. */
class ServerSetupModel(application: Application) : AndroidViewModel(application) {
    data class Form(
        val id: String? = null,
        val type: ServerType = ServerType.Nextcloud,
        val name: String = "",
        /** The address without its scheme; [secure] picks https:// or http://. */
        val url: String = "",
        val secure: Boolean = true,
        val username: String = "",
        val password: String = "",
        val folder: String = "/OpenPro",
        val layout: String = "${com.openpro.media.PathLayout.YEAR}-${com.openpro.media.PathLayout.MONTH}",
        val pinned: String? = null,
        val manualPassword: Boolean = false,
        val busy: String? = null,
        val error: String? = null,
        val untrusted: Pair<String, String>? = null,
        val signedIn: Boolean = false,
        /** Signed in with the Nextcloud app's own app password rather than one of OpenPro's. */
        val viaApp: Boolean = false,
        val saved: Boolean = false,
        /** What the last successful test said; cleared by any change to the form. */
        val connected: String? = null,
    )

    private val store = application.app.servers
    val form = MutableStateFlow(Form())
    private var loaded = false
    private var loginJob: Job? = null
    private val signInWake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    fun load(id: String?) {
        if (loaded) return
        loaded = true
        val p = store.get(id) ?: return
        form.value = Form(
            id = p.id, type = p.type, name = p.name, url = p.baseUrl.substringAfter("://"), secure = !p.isCleartext, username = p.username,
            password = store.password(p.id).orEmpty(), folder = p.folder, layout = p.layout, pinned = p.pinnedCertSha256,
            // Signed out (no saved password, e.g. after importing settings): offer the sign-in choices again.
            manualPassword = store.password(p.id) != null, signedIn = store.password(p.id) != null,
        )
    }

    fun update(change: (Form) -> Form) = form.update { change(it).copy(error = null, connected = null) }

    /** Typing or pasting an address with its scheme sets the https/http switch and drops the scheme. */
    fun setAddress(text: String) = update { f ->
        val scheme = Regex("^\\s*(https?)://", RegexOption.IGNORE_CASE).find(text)
        if (scheme == null) f.copy(url = text)
        else f.copy(url = text.substring(scheme.range.last + 1), secure = scheme.groupValues[1].equals("https", ignoreCase = true))
    }

    private fun normalizedUrl(): String? {
        val f = form.value
        val raw = (if (f.secure) "https://" else "http://") + f.url.trim().substringAfter("://")
        return raw.toHttpUrlOrNull()?.toString()?.trimEnd('/')
    }

    /**
     * Sign-in through the Nextcloud app ([NextcloudAppAccount]) with the account the user picked,
     * checked against the server before it's accepted. If the server won't take it, or the app
     * can't provide it, this carries on with the browser sign-in instead.
     */
    fun signInWithNextcloudApp(activity: android.app.Activity, accountName: String, accountType: String, openBrowser: (String) -> Unit) {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            try {
                form.update { it.copy(busy = "Asking the Nextcloud app for $accountName…", error = null, connected = null) }
                NextcloudAppAccount.parse(accountName)?.let { (_, address) -> form.update { it.copy(url = address) } }
                val c = NextcloudAppAccount.credentials(activity, accountName, accountType)
                form.update { it.copy(busy = "Checking the sign-in with ${c.address}…") }
                val secure = workingScheme(c)
                form.update {
                    it.copy(url = c.address, secure = secure, username = c.loginName, password = c.appPassword, signedIn = true, viaApp = true, busy = null)
                }
            } catch (e: UntrustedCertificateException) {
                // The credentials are fine to keep: once the certificate is trusted, Test and save uses them.
                form.update { it.copy(busy = null, untrusted = e.sha256 to e.subject) }
            } catch (e: android.accounts.OperationCanceledException) {
                form.update { it.copy(busy = null, error = "Access wasn't allowed. Sign in with the browser instead, or try again.") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                form.update { it.copy(busy = null) }
                throw e
            } catch (e: Exception) {
                android.util.Log.i("ServerSetup", "Nextcloud app sign-in failed", e)
                form.update { it.copy(busy = null, username = "", password = "", signedIn = false, viaApp = false) }
                loginJob = null
                signInWithBrowser(openBrowser, reason = "The Nextcloud app couldn't sign in (${describeServerError(e)}), so trying your browser.")
            }
        }
    }

    /**
     * The app's account names have no scheme, so try https:// first and plain http:// only if
     * https can't connect at all. Returns whether https works; throws if the sign-in is refused.
     */
    private suspend fun workingScheme(c: NextcloudAppAccount.Credentials): Boolean {
        fun probe(scheme: String) = ServerClient(
            ServerProfile(type = ServerType.Nextcloud, name = c.address, baseUrl = "$scheme://${c.address}", username = c.loginName, pinnedCertSha256 = form.value.pinned),
            c.appPassword,
        )
        try {
            probe("https").nextcloudUserId()
            return true
        } catch (e: java.io.IOException) {
            if (e is UntrustedCertificateException || e is HttpStatusException) throw e
            try {
                probe("http").nextcloudUserId()
            } catch (_: java.io.IOException) {
                throw e
            }
            return false
        }
    }

    /** Nextcloud Login Flow v2: open the browser, wait for the app password. [reason] says why, when it's a fallback. */
    fun signInWithBrowser(open: (String) -> Unit, reason: String? = null) {
        val url = normalizedUrl() ?: return form.update { it.copy(error = listOfNotNull(reason, "Enter the server's address first.").joinToString(" ")) }
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            try {
                form.update { it.copy(busy = "Contacting $url…", error = null) }
                val login = NextcloudLogin(TrustPolicy(form.value.pinned))
                val session = login.start(url)
                open(session.loginUrl)
                form.update {
                    it.copy(
                        busy = listOfNotNull(
                            reason,
                            "Sign in in your browser, then come back here.",
                        ).joinToString("\n\n"),
                    )
                }
                val result = login.await(session, signInWake) { e ->
                    // Unreachable while away is Android's background network block; on screen, give
                    // a few tries before reporting it.
                    android.util.Log.i("ServerSetup", "sign-in poll failed (on screen: $onScreen): $e")
                    !onScreen || ++pollFailures < 3
                }
                // Keep the address the user entered: the server's own name for itself may not be
                // reachable from the phone (see NextcloudLogin.onto).
                form.update {
                    it.copy(
                        url = url.substringAfter("://"), secure = url.startsWith("https://"),
                        username = result.loginName, password = result.appPassword, signedIn = true, viaApp = false, busy = null,
                    )
                }
            } catch (e: UntrustedCertificateException) {
                form.update { it.copy(busy = null, untrusted = e.sha256 to e.subject) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                form.update { it.copy(busy = null) }
                throw e
            } catch (e: Exception) {
                form.update { it.copy(busy = null, error = describeServerError(e)) }
            }
        }
    }

    @Volatile private var onScreen = true
    private var pollFailures = 0

    /** The user is back (e.g. from the browser): check for the grant now rather than at the next poll. */
    fun screenResumed() {
        onScreen = true
        pollFailures = 0
        signInWake.trySend(Unit)
    }

    fun screenPaused() {
        onScreen = false
    }

    fun cancelSignIn() {
        loginJob?.cancel()
        form.update { it.copy(busy = null) }
    }

    fun trustCertificate() = form.update { it.copy(pinned = it.untrusted?.first, untrusted = null) }

    fun dismissCertificate() = form.update { it.copy(untrusted = null) }

    /** Tests the connection with the form's settings and saves the server if it works. */
    fun testAndSave(onSaved: (String) -> Unit) {
        val f = form.value
        val url = normalizedUrl() ?: return form.update { it.copy(error = "That address doesn't look right") }
        if (f.username.isBlank() || f.password.isBlank()) {
            return form.update { it.copy(error = if (f.type == ServerType.Nextcloud) "Sign in with the browser, or enter a user name and app password" else "Enter a user name and password") }
        }
        viewModelScope.launch {
            form.update { it.copy(busy = "Testing the connection…", error = null, connected = null) }
            try {
                var profile = ServerProfile(
                    id = f.id ?: java.util.UUID.randomUUID().toString(),
                    type = f.type,
                    name = f.name.ifBlank { url.toHttpUrlOrNull()?.host ?: url },
                    baseUrl = url,
                    username = f.username.trim(),
                    folder = "/" + f.folder.trim().trim('/').ifEmpty { "OpenPro" },
                    layout = f.layout,
                    pinnedCertSha256 = f.pinned,
                )
                if (profile.type == ServerType.Nextcloud) {
                    val userId = ServerClient(profile, f.password).nextcloudUserId()
                    if (userId != null && userId != profile.username) profile = profile.copy(davUser = userId)
                }
                val message = ServerClient(profile, f.password).test()
                store.save(profile, f.password)
                // Uploads waiting for this server to be signed in to can go now.
                com.openpro.batch.BatchScheduler.kick(getApplication())
                form.update { it.copy(busy = null, saved = true, error = null, id = profile.id, connected = message) }
                onSaved(profile.id)
            } catch (e: UntrustedCertificateException) {
                form.update { it.copy(busy = null, untrusted = e.sha256 to e.subject) }
            } catch (e: Exception) {
                form.update { it.copy(busy = null, error = describeServerError(e)) }
            }
        }
    }
}

/** Add or edit a server. */
@Composable
fun ServerEditScreen(vm: AppViewModel, id: String?, onDone: () -> Unit, onBack: () -> Unit) {
    val model: ServerSetupModel = viewModel(key = "server-${id ?: "new"}")
    val form by model.form.collectAsState()
    val context = LocalContext.current
    remember(id) { model.load(id); true }
    DisposableEffect(Unit) { onDispose { model.cancelSignIn() } }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        model.screenResumed()
        onPauseOrDispose { model.screenPaused() }
    }

    Screen(if (id == null) "Add server" else "Edit server", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerType.entries.forEach { t ->
                    FilterChip(selected = form.type == t, onClick = { model.update { it.copy(type = t) } }, label = { Text(t.label) })
                }
            }
            if (form.type == ServerType.Nextcloud) {
                NextcloudSignIn(model, form)
            } else {
                AddressField(model, form)
                Credentials(model, form)
            }

            OutlinedTextField(
                form.folder, { v -> model.update { it.copy(folder = v) } },
                label = { Text("Upload folder") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Created if missing.") },
            )
            FolderLayout(
                "Folders inside it", "the upload folder", com.openpro.media.PathLayout.uploadPresets, form.layout,
            ) { v -> model.update { it.copy(layout = v) } }
            OutlinedTextField(
                form.name, { v -> model.update { it.copy(name = v) } },
                label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            form.pinned?.let { Text("Trusting the certificate with SHA-256 ${it.chunked(2).joinToString(":")}", style = MaterialTheme.typography.bodySmall) }

            form.busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { model.cancelSignIn() }) { Text("Cancel") }
                }
            }
            form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            form.connected?.let { SuccessLine(it) }
            if (form.connected != null) {
                Button(onClick = onDone, Modifier.fillMaxWidth()) { Text("Done") }
            } else {
                Button(
                    onClick = { model.testAndSave { savedId -> vm.serverSaved(savedId) } },
                    enabled = form.busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Test and save") }
                OutlinedButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }

    form.untrusted?.let { (sha, subject) ->
        AlertDialog(
            onDismissRequest = { model.dismissCertificate() },
            title = { Text("Untrusted certificate") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The server's certificate isn't signed by an authority Android trusts. That's normal for a self-signed certificate on your own server.")
                    Text("Issued to: $subject", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text("SHA-256: ${sha.chunked(2).joinToString(":")}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Only trust it if this fingerprint matches your server's certificate. OpenPro will then accept exactly " +
                            "this certificate for this server, and refuse if it ever changes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { model.trustCertificate() }) { Text("Trust this certificate") } },
            dismissButton = { TextButton(onClick = { model.dismissCertificate() }) { Text("Cancel") } },
        )
    }
}
