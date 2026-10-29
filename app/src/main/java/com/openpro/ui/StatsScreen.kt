package com.openpro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.openpro.copies.MediaStats
import com.openpro.copies.Safety
import com.openpro.copies.SafetyCounts
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Each safety group's colour (the reserved status steps: good, warning, critical), icon and name. */
private val Safety.color
    get() = when (this) {
        Safety.BackedUp -> Color(0xFF0CA30C)
        Safety.PhoneOnly -> Color(0xFFFAB219)
        Safety.NotSaved -> Color(0xFFD03B3B)
    }
private val Safety.icon: ImageVector
    get() = when (this) {
        Safety.BackedUp -> Icons.Filled.CloudDone
        Safety.PhoneOnly -> Icons.Filled.PhoneAndroid
        Safety.NotSaved -> Icons.Filled.ReportProblem
    }
private val Safety.label
    get() = when (this) {
        Safety.BackedUp -> "Backed up to a server"
        Safety.PhoneOnly -> "Only on this phone"
        Safety.NotSaved -> "Not saved yet"
    }
private val Safety.hint
    get() = when (this) {
        Safety.BackedUp -> "A full-quality copy is on your server"
        Safety.PhoneOnly -> "Saved, but not off the phone yet"
        Safety.NotSaved -> "Only on the camera, or just its preview saved"
    }

/**
 * Summary statistics of every clip OpenPro knows: how many, how much, how safe (backed up to a
 * server, only on this phone, or not saved), when they were recorded, and where the space goes.
 */
@Composable
fun StatsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val clips by vm.catalogue.collectAsState()
    val copies by vm.copyIndex.collectAsState()
    val stats = remember(clips, copies) { MediaStats.of(clips.values, copies) }

    Screen("Stats", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (stats.clips == 0) {
                Text(
                    "Nothing here yet. Browse a camera's media and its items are counted here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            // As tall as the tallest tile, so all three line up.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val pct = stats.safety.backedUp * 100 / stats.clips
                val tile = Modifier.weight(1f).fillMaxHeight()
                StatTile("Media items", "${stats.clips}", "${stats.videos} videos · ${stats.photos} photos", tile)
                StatTile("Footage", formatBytes(stats.footageBytes), "as recorded", tile)
                StatTile("Backed up", "$pct%", "${stats.safety.backedUp} of ${stats.clips}", tile)
            }
            Section("How safe your media is") { SafetySection(stats) }
            if (stats.byMonth.isNotEmpty()) Section("Recorded each month") { MonthChart(stats.byMonth) }
            Section("Space used") { SpaceSection(vm, stats) }
            if (stats.byCamera.size > 1) {
                Section("By camera") {
                    stats.byCamera.forEach { (serial, counts) ->
                        val name = vm.camera.saved.bySerial(serial)?.name ?: "Another camera"
                        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row {
                                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text("${counts.total} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            SafetyBar(counts, height = 8)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, sub: String, modifier: Modifier) {
    // A unit after the number ("12.34 GB") is set smaller, so the number keeps its size and the unit still fits.
    val number = value.substringBefore(' ')
    val unit = value.substringAfter(' ', "")
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    append(number)
                    if (unit.isNotEmpty()) {
                        withStyle(androidx.compose.ui.text.SpanStyle(fontSize = MaterialTheme.typography.titleSmall.fontSize)) { append(" $unit") }
                    }
                },
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
            )
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** The safety bar, then one row per group: its icon and name, count, share and size (the legend doubles as the table). */
@Composable
private fun SafetySection(stats: MediaStats) {
    SafetyBar(stats.safety, height = 20)
    Safety.entries.forEach { s ->
        val n = stats.safety[s]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(s.icon, null, Modifier.size(22.dp), tint = s.color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.label, style = MaterialTheme.typography.bodyMedium)
                Text(s.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$n · ${n * 100 / stats.clips}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(formatBytes(stats.safetyBytes[s] ?: 0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A horizontal bar split by safety group, a 2dp gap between groups, rounded ends. */
@Composable
private fun SafetyBar(counts: SafetyCounts, height: Int) {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        val r = CornerRadius(4.dp.toPx())
        val outline = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, r)) }
        clipPath(outline) {
            drawRect(empty)
            if (counts.total == 0) return@clipPath
            val gap = 2.dp.toPx()
            val shown = Safety.entries.filter { counts[it] > 0 }
            val usable = size.width - gap * (shown.size - 1)
            var x = 0f
            shown.forEach { s ->
                val w = usable * counts[s] / counts.total
                drawRect(s.color, Offset(x, 0f), Size(w, size.height))
                x += w + gap
            }
        }
    }
}

/**
 * Clips recorded each month, stacked by safety, for the last 12 months up to the latest one with
 * clips. Tap a month for its numbers.
 */
@Composable
private fun MonthChart(byMonth: List<Pair<YearMonth, SafetyCounts>>) {
    val last = byMonth.last().first
    val first = maxOf(byMonth.first().first, last.minusMonths(11))
    val known = byMonth.toMap()
    val months = generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= last }.toList()
    val counts = months.map { known[it] ?: SafetyCounts() }
    val max = counts.maxOf { it.total }.coerceAtLeast(1)
    var picked by rememberSaveable { mutableStateOf(months.lastIndex) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val pick = MaterialTheme.colorScheme.onSurface

    // The picked month, in words: the chart's tooltip, tapped rather than hovered.
    val c = counts[picked.coerceIn(0, counts.lastIndex)]
    val month = months[picked.coerceIn(0, months.lastIndex)]
    Text(
        "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}: ${c.total} items" +
            if (c.total > 0) " · ${c.backedUp} backed up · ${c.phoneOnly} only on phone · ${c.notSaved} not saved" else "",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$max", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Canvas(
        Modifier.fillMaxWidth().height(140.dp).pointerInput(months.size) {
            detectTapGestures { p -> picked = (p.x / (size.width / months.size)).toInt().coerceIn(0, months.lastIndex) }
        },
    ) {
        val slot = size.width / months.size
        val barW = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        val gap = 2.dp.toPx()
        // Top gridline (the max) and the baseline.
        drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        counts.forEachIndexed { i, m ->
            val x = slot * i + (slot - barW) / 2
            if (i == picked) {
                drawRoundRect(pick.copy(alpha = 0.08f), Offset(slot * i, 0f), Size(slot, size.height), CornerRadius(6.dp.toPx()))
            }
            var y = size.height
            val shown = Safety.entries.filter { m[it] > 0 }
            shown.forEachIndexed { j, s ->
                val h = size.height * m[s] / max - if (j < shown.lastIndex) gap else 0f
                // Only the top segment gets rounded corners; the rest sit square on each other.
                if (j == shown.lastIndex) {
                    drawRoundRect(s.color, Offset(x, y - h), Size(barW, h), CornerRadius(4.dp.toPx()))
                    drawRect(s.color, Offset(x, y - h / 2), Size(barW, h / 2))
                } else {
                    drawRect(s.color, Offset(x, y - h), Size(barW, h))
                }
                y -= h + gap
            }
        }
    }
    // Month initials under the bars, with the year under January (and under the first month).
    Row(Modifier.fillMaxWidth()) {
        months.forEachIndexed { i, m ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    m.month.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == picked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (i == 0 || m.monthValue == 1) {
                    Text("${m.year % 100}".padStart(2, '0').let { "’$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * How full each device is. A camera's card (while it's connected: it reports its free space) and
 * each of the phone's volumes get a bar of the whole device: your media, everything else, free.
 * Servers don't say how big they are, so they get their totals. Each line says what the files are.
 */
@Composable
private fun SpaceSection(vm: AppViewModel, stats: MediaStats) {
    val media = MaterialTheme.colorScheme.primary
    val other = MaterialTheme.colorScheme.outline
    val free = MaterialTheme.colorScheme.surfaceVariant
    val usage by produceState<List<com.openpro.storage.Storage.Usage>?>(null) { value = vm.storage.usage() }
    val bluetooth by vm.camera.ble.connected.collectAsState()
    val cardFree by produceState<Long?>(null, bluetooth) { value = if (bluetooth) vm.control.sdFreeBytes() else null }
    val connected = vm.camera.current?.serial

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Swatch(media, "Your media")
        Swatch(other, "Everything else")
        Swatch(free, "Free")
    }

    stats.onCards.forEach { (serial, files) ->
        val name = vm.camera.saved.bySerial(serial)?.name ?: "Another camera"
        val freeHere = cardFree.takeIf { serial != null && serial == connected }
        Device(
            "$name's card",
            if (freeHere != null) "${formatBytes(files.bytes)} of media · ${formatBytes(freeHere)} free"
            else "${formatBytes(files.bytes)} of media · connect to it to see free space",
            breakdown(files, previewsName = "camera previews"),
            segments = freeHere?.let { listOf(media to files.bytes) },
            total = freeHere?.let { it + files.bytes },
        )
    }

    when (val volumes = usage) {
        null -> Text("Measuring the phone's storage…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> volumes.filter { it.total > 0 }.forEach { u ->
            val rest = (u.total - u.free - u.openPro).coerceAtLeast(0)
            Device(
                u.volume.shortLabel,
                "OpenPro ${formatBytes(u.openPro)} · ${formatBytes(u.free)} free of ${formatBytes(u.total)}",
                null,
                segments = listOf(media to u.openPro, other to rest),
                total = u.total,
            )
        }
    }
    if (stats.phone.count > 0) {
        Text(
            "Saved by OpenPro on this phone: ${formatBytes(stats.phone.bytes)} in ${stats.phone.count} files · ${breakdown(stats.phone)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    stats.byServer.forEach { (id, files) ->
        Device(
            vm.servers.get(id)?.name ?: "A removed server",
            "${formatBytes(files.bytes)} uploaded in ${files.count} files",
            breakdown(files),
            segments = null,
            total = null,
        )
    }
}

/** One device: its name and summary, what its files are, and (if its size is known) a bar of the whole of it. */
@Composable
private fun Device(name: String, summary: String, detail: String?, segments: List<Pair<Color, Long>>?, total: Long?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        if (segments != null && total != null && total > 0) FillBar(segments, total)
        Text(summary, style = MaterialTheme.typography.bodySmall)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A bar of a whole device: [segments] from the left, a 2dp gap between them, and what's left over (free) as the track. */
@Composable
private fun FillBar(segments: List<Pair<Color, Long>>, total: Long) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val r = CornerRadius(4.dp.toPx())
        val outline = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, r)) }
        clipPath(outline) {
            drawRect(track)
            val gap = 2.dp.toPx()
            var x = 0f
            segments.filter { it.second > 0 }.forEach { (color, bytes) ->
                val w = (size.width * bytes / total).coerceAtLeast(2.dp.toPx())
                drawRect(color, Offset(x, 0f), Size(w, size.height))
                x += w + gap
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(10.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(2.dp.toPx())) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What a set of files is: "originals 10.1 GB · stabilized 2.00 GB · previews 210 MB". */
private fun breakdown(f: com.openpro.copies.Files, previewsName: String = "previews") = listOfNotNull(
    f.original.takeIf { it > 0 }?.let { "originals ${formatBytes(it)}" },
    f.stabilized.takeIf { it > 0 }?.let { "stabilized ${formatBytes(it)}" },
    f.preview.takeIf { it > 0 }?.let { "$previewsName ${formatBytes(it)}" },
).joinToString(" · ")
