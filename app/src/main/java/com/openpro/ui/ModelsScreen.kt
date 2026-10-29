package com.openpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openpro.camera.CameraModel
import com.openpro.camera.CameraModels
import com.openpro.camera.Feature
import com.openpro.camera.Support

/** How the list is ordered. */
private enum class ModelSort(val label: String) {
    SupportedFirst("Supported first"),
    Newest("Newest"),
    Oldest("Oldest"),
    Name("Name"),
}

/** The chips that narrow the list: support, then each feature. A camera must match every chip that's on. */
private sealed class ModelFilter(val label: String) {
    abstract fun matches(m: CameraModel): Boolean

    data object Supported : ModelFilter("Supported") {
        override fun matches(m: CameraModel) = m.support.usable
    }

    data object Tested : ModelFilter("Tested") {
        override fun matches(m: CameraModel) = m.support == Support.Supported
    }

    data class Has(val feature: Feature) : ModelFilter(feature.label) {
        override fun matches(m: CameraModel) = feature in m.features
    }

    companion object {
        val all = listOf(Supported, Tested) + Feature.entries.map(::Has)
    }
}

/** Every GoPro model: whether OpenPro works with it and what it offers, searchable by name and filterable by chip. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ModelSort.SupportedFirst) }
    var on by rememberSaveable { mutableStateOf(setOf<String>()) }
    var sorting by remember { mutableStateOf(false) }
    val filters = ModelFilter.all.filter { it.label in on }
    val models = CameraModels.all
        .filter { m -> m.displayName.contains(query.trim(), ignoreCase = true) && filters.all { it.matches(m) } }
        .sortedWith(
            when (sort) {
                ModelSort.SupportedFirst -> compareBy({ it.support.ordinal }, { -it.year })
                ModelSort.Newest -> compareBy({ -it.year }, { it.displayName })
                ModelSort.Oldest -> compareBy({ it.year }, { it.displayName })
                ModelSort.Name -> compareBy { it.displayName }
            },
        )

    Screen("Camera models", onBack = onBack, actions = {
        IconButton(onClick = { sorting = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
        DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
            ModelSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    leadingIcon = { if (s == sort) Icon(Icons.Filled.Check, null) },
                    onClick = { sort = s; sorting = false },
                )
            }
        }
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search models") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                    },
                    singleLine = true,
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModelFilter.all.forEach { f ->
                        FilterChip(
                            selected = f.label in on,
                            onClick = { on = if (f.label in on) on - f.label else on + f.label },
                            label = { Text(f.label) },
                        )
                    }
                }
            }
            if (models.isEmpty()) {
                item {
                    Text(
                        "No models match.",
                        Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(models, key = { it.modelId }) { ModelCard(it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(m: CameraModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${m.year}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(m.support)
            }
            val chips = buildList {
                if (m.support == Support.Supported) add("Tested")
                m.features.sortedBy { it.ordinal }.forEach { add(it.label) }
            }
            if (chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { Pill(it) }
                }
            }
        }
    }
}

/** Whether OpenPro works with the camera: green if it does, plain otherwise. */
@Composable
private fun StatusChip(support: Support) {
    val (label, usable) = when (support) {
        Support.Supported, Support.Untested -> "Supported" to true
        Support.Planned -> "Not yet" to false
        Support.Legacy -> "Not supported" to false
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (usable) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (usable) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** A feature, small and not tappable. */
@Composable
private fun Pill(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
    }
}
