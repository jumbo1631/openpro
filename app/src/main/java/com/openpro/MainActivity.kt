package com.openpro

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.openpro.ui.AppViewModel
import com.openpro.ui.ClipScreen
import com.openpro.ui.ConnectScreen
import com.openpro.ui.GalleryScreen
import com.openpro.ui.ModelsScreen
import com.openpro.ui.StorageScreen

/** Material motion's shared X axis transition, for moving between screens. */
/**
 * Plain slides: a new screen slides in from the right over the old one, which moves a little to
 * the left; going back reverses it. Used for back gestures too, so nothing shrinks.
 */
private object NavMotion {
    private const val DURATION = 300
    private val easing = androidx.compose.animation.core.FastOutSlowInEasing

    /** In from the right, its full width. */
    fun open() = slideInHorizontally(tween(DURATION, easing = easing)) { it }

    /** The screen left behind, moving a third of the way left. */
    fun cover() = slideOutHorizontally(tween(DURATION, easing = easing)) { -it / 3 }

    /** Going back: the screen underneath returns from the left. */
    fun uncover() = slideInHorizontally(tween(DURATION, easing = easing)) { -it / 3 }

    /** Going back: the screen on top slides off to the right. */
    fun close() = slideOutHorizontally(tween(DURATION, easing = easing)) { it }
}

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    /** Android's "turn on Bluetooth?" prompt; the answer goes back to the view model. */
    private val turnOnBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bluetoothAnswer?.invoke(it.resultCode == RESULT_OK)
    }
    private var bluetoothAnswer: ((Boolean) -> Unit)? = null
    /** Set when opened from a batch notification. */
    private val openBatch = kotlinx.coroutines.flow.MutableStateFlow(false)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(com.openpro.batch.BatchWorker.EXTRA_OPEN_BATCH, false)) openBatch.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the status and navigation bars always need light icons; the
        // default follows the phone's theme and gives dark icons on a dark app in light mode.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (intent.getBooleanExtra(com.openpro.batch.BatchWorker.EXTRA_OPEN_BATCH, false)) openBatch.value = true
        permissions.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00A3E0), secondary = Color(0xFF00A3E0))) {
                val nav = rememberNavController()
                val vm: AppViewModel = viewModel()
                com.openpro.ui.MessageToasts(vm)
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    bluetoothAnswer = vm::bluetoothAnswered
                    vm.bluetoothRequests.collect {
                        try {
                            turnOnBluetooth.launch(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        } catch (e: SecurityException) {
                            // Without the Bluetooth permission the prompt isn't allowed: the system setting is.
                            vm.bluetoothAnswered(false)
                            startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    }
                }
                // Files deleted in another app (a gallery, a file manager) drop out on coming back.
                androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                    vm.refreshCopies()
                    onPauseOrDispose {}
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    openBatch.collect { open ->
                        if (open) {
                            openBatch.value = false
                            nav.navigate("batch") { launchSingleTop = true }
                        }
                    }
                }
                // Every screen's connection bar reads the app's view model through this.
                androidx.compose.runtime.CompositionLocalProvider(com.openpro.ui.LocalAppViewModel provides vm) {
                // Plain slides both ways. The back gesture has its own transitions, which default to
                // shrinking the screen; they're the same slides here, following the finger.
                // First launch: offer stabilization before the home screen.
                val start = androidx.compose.runtime.remember {
                    if (com.openpro.ui.FirstRun.offerStabilization(this@MainActivity)) "setup" else "connect"
                }
                NavHost(
                    nav, startDestination = start,
                    enterTransition = { NavMotion.open() },
                    exitTransition = { NavMotion.cover() },
                    popEnterTransition = { NavMotion.uncover() },
                    popExitTransition = { NavMotion.close() },
                    predictivePopEnterTransition = { NavMotion.uncover() },
                    predictivePopExitTransition = { NavMotion.close() },
                ) {
                    composable("setup") {
                        com.openpro.ui.SetupScreen(onDone = {
                            com.openpro.ui.FirstRun.stabilizationOffered(this@MainActivity)
                            nav.navigate("connect") { popUpTo("setup") { inclusive = true } }
                        })
                    }
                    composable("connect") {
                        ConnectScreen(
                            vm,
                            onBrowse = { nav.navigate("gallery") },
                            onModels = { nav.navigate("models") },
                            onNetwork = { nav.navigate("network") },
                            onStorage = { nav.navigate("storage") },
                            onBatch = { nav.navigate("batch") { launchSingleTop = true } },
                            onServers = { nav.navigate("servers") },
                            onRemote = { nav.navigate("remote") },
                            onAbout = { nav.navigate("about") },
                        )
                    }
                    composable("gallery") {
                        GalleryScreen(
                            vm,
                            onOpen = { nav.navigate("clip/" + android.net.Uri.encode(it)) },
                            onBatch = { nav.navigate("batch") { launchSingleTop = true } },
                            onServers = { nav.navigate("servers") },
                            onStats = { nav.navigate("stats") },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("stats") { com.openpro.ui.StatsScreen(vm, onBack = { nav.popBackStack() }) }
                    composable("clip/{id}") { entry ->
                        val id = android.net.Uri.decode(entry.arguments?.getString("id").orEmpty())
                        ClipScreen(
                            vm, id,
                            onBatch = { nav.navigate("batch") { launchSingleTop = true } },
                            onServers = { nav.navigate("servers") },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("batch") {
                        com.openpro.ui.BatchScreen(vm, onServers = { nav.navigate("servers") }, onBack = { nav.popBackStack() })
                    }
                    composable("servers") {
                        com.openpro.ui.ServersScreen(
                            vm,
                            onEdit = { id -> nav.navigate("server/" + (id ?: "new")) },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("server/{id}") { entry ->
                        val id = entry.arguments?.getString("id")?.takeIf { it != "new" }
                        com.openpro.ui.ServerEditScreen(vm, id, onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
                    }
                    composable("remote") { com.openpro.ui.RemoteScreen(vm, onBack = { nav.popBackStack() }) }
                    composable("models") { ModelsScreen(onBack = { nav.popBackStack() }) }
                    composable("about") { com.openpro.ui.AboutScreen(vm, onBack = { nav.popBackStack() }) }
                    composable("network") { com.openpro.ui.NetworkScreen(vm, onBack = { nav.popBackStack() }) }
                    composable("storage") { StorageScreen(vm, onBack = { nav.popBackStack() }) }
                }
                }
            }
        }
    }
}
