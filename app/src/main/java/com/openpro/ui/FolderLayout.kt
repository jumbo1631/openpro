package com.openpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * How copies are arranged in folders: named layouts as chips, and the pattern itself for anything
 * else. Changing it affects new copies only; nothing already saved is moved.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FolderLayout(
    title: String, inside: String, presets: List<com.openpro.media.PathLayout.Preset>, pattern: String, onChange: (String) -> Unit,
) {
    // What's typed, kept as typed ("{camera}/" mid-word); a chip replaces it.
    var text by remember { mutableStateOf(pattern) }
    androidx.compose.runtime.LaunchedEffect(pattern) { if (clean(text) != pattern) text = pattern }
    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { p -> FilterChip(selected = pattern == p.pattern, onClick = { onChange(p.pattern) }, label = { Text(p.label) }) }
    }
    androidx.compose.material3.OutlinedTextField(
        text, { v -> text = v; onChange(clean(v)) },
        label = { Text("Pattern") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = {
            val example = com.openpro.media.PathLayout.resolve(
                text, com.openpro.media.CameraMedia("100GOPRO", "GX010849.MP4", 0, 1_787_581_424, 0, 0, null, null),
                "HERO12 Black", "HERO12 Black",
            )
            Text(
                "Inside $inside, e.g. ${example ?: "no folders"}. Use ${com.openpro.media.PathLayout.tokens.joinToString(" ")}. " +
                    "Applies to new copies; existing ones stay where they are.",
            )
        },
    )
}

private fun clean(pattern: String) = pattern.trim().trim('/')
