package com.openpro.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        bottomBar = bottomBar,
        topBar = {
            androidx.compose.foundation.layout.Column {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
                actions = actions,
            )
            ConnectionBar()
            }
        },
        // Content stays clear of the keyboard, so a focused text field is scrolled into view by the
        // screen alone (with the window resized, not panned); otherwise the two fight on every keystroke.
        contentWindowInsets = androidx.compose.material3.ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        content = content,
    )
}

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes < 1024L * 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
    else -> String.format(Locale.US, "%.2f TB", bytes / 1024.0 / 1024 / 1024 / 1024)
}

/**
 * For screens showing camera content: while the screen is visible (started), the batch won't
 * switch the phone off the camera's Wi-Fi.
 */
@Composable
fun HoldCameraWhileVisible(vm: AppViewModel) {
    androidx.lifecycle.compose.LifecycleStartEffect(Unit) {
        val release = vm.camera.holdForUi()
        onStopOrDispose { release() }
    }
}

/** Icon that opens the batch page, with the number of unfinished jobs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchButton(vm: AppViewModel, onClick: () -> Unit) {
    val active by vm.activeJobCount.collectAsState()
    // The badge sits on top of the button, not inside it: the button clips its content to its circle.
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = onClick) {
            Icon(androidx.compose.material.icons.Icons.Filled.SwapVert, "Batch")
        }
        if (active > 0) {
            androidx.compose.material3.Badge(
                androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 4.dp),
            ) { Text("$active") }
        }
    }
}

/**
 * A message that can run long (a failed connection's error), kept to [maxLines]. When it's cut
 * short, tapping it shows the whole message in a dialog.
 */
@Composable
fun ShortMessage(
    text: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    maxLines: Int = 2,
    title: String = "What happened",
) {
    var cut by androidx.compose.runtime.remember(text) { androidx.compose.runtime.mutableStateOf(false) }
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Cut short: the whole line opens it, and an arrow at the end says so.
    androidx.compose.foundation.layout.Row(
        modifier.then(if (cut) androidx.compose.ui.Modifier.clickable { open = true } else androidx.compose.ui.Modifier),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text,
            androidx.compose.ui.Modifier.weight(1f, fill = false),
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            onTextLayout = { cut = it.hasVisualOverflow },
        )
        if (cut) {
            Icon(
                Icons.Filled.ArrowOutward, "Show the whole message",
                androidx.compose.ui.Modifier.size(16.dp),
                tint = if (color == androidx.compose.ui.graphics.Color.Unspecified) androidx.compose.material3.LocalContentColor.current else color,
            )
        }
    }
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(text) } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}
