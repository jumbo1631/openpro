package com.openpro.ui

import android.content.Context
import com.openpro.media.CameraMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SortOrder(val label: String) {
    NewestFirst("Date, newest first"),
    OldestFirst("Date, oldest first"),
    NameAZ("File name, A–Z"),
    LargestFirst("Size, largest first"),
    SmallestFirst("Size, smallest first"),
}

enum class Layout(val label: String) { Grid("Grid"), List("List") }

enum class TileSize(val label: String, val minWidthDp: Int) {
    Small("Small", 110),
    Medium("Medium", 160),
    Large("Large", 240),
}

/** What a grid tile shows. The list layout always shows everything. */
enum class TileField(val label: String) {
    FileName("Original file name"),
    Date("Date"),
    Time("Time"),
    Duration("Duration"),
    FileSize("File size"),
    PreviewSize("Preview size"),
}

/** How the gallery is broken into sections ("shelves") when sorted by date. */
enum class Grouping(val label: String) { None("None"), Day("Day"), Month("Month") }

/**
 * Something a clip can be filtered on: its format, where it is, or whether it has been stabilized.
 * Format is a choice of one (Video or Photo); the others can also be negated ([off]).
 */
enum class Facet(val on: String, val off: String?) {
    Video("Video", null),
    Photo("Photo", null),
    Camera("On camera", "Not on camera"),
    Phone("On this phone", "Not on this phone"),
    Server("On a server", "Not on a server"),
    Stabilized("Stabilized", "Not stabilized"),
    ;

    val isFormat get() = this == Video || this == Photo
}

/** One gallery filter: clips with [facet], or, when [negated], clips without it. */
data class ClipFilter(val facet: Facet, val negated: Boolean = false) {
    val label get() = if (negated) facet.off ?: facet.on else facet.on
}

/** [filters] with [facet] set to [state]: true (has it), false (hasn't), or null (either). Video and Photo replace each other. */
fun withFacet(filters: List<ClipFilter>, facet: Facet, state: Boolean?): List<ClipFilter> {
    val rest = filters.filter { it.facet != facet && !(facet.isFormat && it.facet.isFormat) }
    return if (state == null) rest else rest + ClipFilter(facet, negated = !state)
}

data class GalleryOptions(
    /** Filters stacked in the order they were added; a clip must pass every one. Empty shows everything. */
    val filters: List<ClipFilter> = emptyList(),
    /** File size bounds in bytes, inclusive; null for no bound. */
    val minSize: Long? = null,
    val maxSize: Long? = null,
    /** Only clips from this camera (serial number); null for every camera. */
    val camera: String? = null,
    val sort: SortOrder = SortOrder.NewestFirst,
    val layout: Layout = Layout.Grid,
    val tileSize: TileSize = TileSize.Medium,
    val fields: Set<TileField> = TileField.entries.toSet() - TileField.PreviewSize,
    /** Break the gallery into day or month sections ("August 2026"); only meaningful for date sorts. */
    val grouping: Grouping = Grouping.Month,
) {
    val canShelve get() = sort == SortOrder.NewestFirst || sort == SortOrder.OldestFirst

    val sizeFiltered get() = minSize != null || maxSize != null

    /** The clips [filters], the size bounds and [camera] let through, given where each is ([clips], [copies]). */
    fun filter(
        media: List<CameraMedia>, clips: Map<String, com.openpro.copies.ClipEntity>, copies: Map<String, List<com.openpro.copies.CopyEntity>>,
    ): List<CameraMedia> = media.filter { m ->
        val clip = clips[m.id]
        val saved = copies[m.id].orEmpty()
        (camera == null || clip?.cameraSerial == camera) &&
            (minSize == null || m.sizeBytes >= minSize) && (maxSize == null || m.sizeBytes <= maxSize) &&
            filters.all { f ->
            val has = when (f.facet) {
                Facet.Video -> m.isVideo
                Facet.Photo -> !m.isVideo
                Facet.Camera -> clip?.onCamera == true
                Facet.Phone -> saved.any { it.isLocal }
                Facet.Server -> saved.any { it.place == com.openpro.copies.CopyPlace.Server }
                Facet.Stabilized -> saved.any { it.version is com.openpro.media.ClipVersion.Stabilized }
            }
            has != f.negated
        }
    }

    /** Grouped into shelves: by day or month, when sorting by date. */
    val shelved get() = grouping != Grouping.None && canShelve

    /** Sorted media grouped into (day or month heading, items), or one unnamed group when not shelving. */
    fun shelves(media: List<CameraMedia>): List<Pair<String?, List<CameraMedia>>> {
        val ordered = sorted(media)
        if (!shelved) return listOf(null to ordered)
        val label: (CameraMedia) -> String = if (grouping == Grouping.Day) MediaTime::day else MediaTime::month
        val groups = LinkedHashMap<String, MutableList<CameraMedia>>()
        for (item in ordered) groups.getOrPut(label(item)) { mutableListOf() }.add(item)
        return groups.map { (month, items) -> month to items }
    }

    fun sorted(media: List<CameraMedia>): List<CameraMedia> = when (sort) {
        SortOrder.NewestFirst -> media.sortedWith(compareByDescending<CameraMedia> { it.createdSec }.thenByDescending { it.name })
        SortOrder.OldestFirst -> media.sortedWith(compareBy<CameraMedia> { it.createdSec }.thenBy { it.name })
        SortOrder.NameAZ -> media.sortedBy { it.name }
        SortOrder.LargestFirst -> media.sortedByDescending { it.sizeBytes }
        SortOrder.SmallestFirst -> media.sortedBy { it.sizeBytes }
    }
}

/** Persists [GalleryOptions] in SharedPreferences. */
class GalleryOptionsStore(context: Context) {
    private val prefs = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)
    private val _options = MutableStateFlow(load())
    val options: StateFlow<GalleryOptions> = _options

    fun update(transform: (GalleryOptions) -> GalleryOptions) {
        val next = transform(_options.value)
        _options.value = next
        prefs.edit()
            .putString("filters", next.filters.joinToString(",") { (if (it.negated) "-" else "") + it.facet.name })
            .remove("source")
            .putLong("minSize", next.minSize ?: -1)
            .putLong("maxSize", next.maxSize ?: -1)
            .putString("camera", next.camera)
            .putString("sort", next.sort.name)
            .putString("layout", next.layout.name)
            .putString("tileSize", next.tileSize.name)
            .putStringSet("fields", next.fields.map { it.name }.toSet())
            .putInt("fieldsVersion", FIELDS_VERSION)
            .putString("grouping", next.grouping.name)
            .apply()
    }

    private fun load(): GalleryOptions {
        val defaults = GalleryOptions()
        return GalleryOptions(
            filters = loadFilters(),
            minSize = prefs.getLong("minSize", -1).takeIf { it >= 0 },
            maxSize = prefs.getLong("maxSize", -1).takeIf { it >= 0 },
            camera = prefs.getString("camera", null),
            sort = enumOr(prefs.getString("sort", null), defaults.sort),
            layout = enumOr(prefs.getString("layout", null), defaults.layout),
            tileSize = enumOr(prefs.getString("tileSize", null), defaults.tileSize),
            fields = prefs.getStringSet("fields", null)
                ?.mapNotNull { name -> TileField.entries.firstOrNull { it.name == name } }?.toSet()
                ?.let(::upgradeFields)
                ?: defaults.fields,
            // Saved before day grouping existed: a month on/off switch.
            grouping = prefs.getString("grouping", null)?.let { enumOr(it, defaults.grouping) }
                ?: if (prefs.getBoolean("shelveByMonth", true)) Grouping.Month else Grouping.None,
        )
    }

    /** "Camera,-Phone" (a minus for "not"); saved before filters stacked, the one "source" choice becomes one filter. */
    private fun loadFilters(): List<ClipFilter> {
        val saved = prefs.getString("filters", null)
            ?: return listOfNotNull(Facet.entries.firstOrNull { it.name == prefs.getString("source", null) }?.let(::ClipFilter))
        return saved.split(",").filter { it.isNotBlank() }.mapNotNull { token ->
            val negated = token.startsWith("-")
            Facet.entries.firstOrNull { it.name == token.removePrefix("-") }?.let { ClipFilter(it, negated) }
        }
    }

    /** Field choices saved before Duration existed get it, and lose preview size, once (the new defaults). */
    private fun upgradeFields(saved: Set<TileField>): Set<TileField> =
        if (prefs.getInt("fieldsVersion", 1) >= FIELDS_VERSION) saved else saved + TileField.Duration - TileField.PreviewSize

    private companion object {
        const val FIELDS_VERSION = 2
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}

/**
 * The camera stamps files with its own wall-clock time and no time zone, so the timestamp is
 * shown as-is (formatted in UTC) to match what the camera displayed when recording.
 */
object MediaTime {
    private val date = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
    private val month = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
    private val day = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault())

    fun month(media: CameraMedia): String =
        if (media.createdSec <= 0) "Unknown date" else month.format(Instant.ofEpochSecond(media.createdSec).atOffset(ZoneOffset.UTC))

    /** "Monday 24 August 2026". */
    fun day(media: CameraMedia): String =
        if (media.createdSec <= 0) "Unknown date" else day.format(Instant.ofEpochSecond(media.createdSec).atOffset(ZoneOffset.UTC))

    fun date(media: CameraMedia): String =
        if (media.createdSec <= 0) "Unknown date" else date.format(Instant.ofEpochSecond(media.createdSec).atOffset(ZoneOffset.UTC))

    fun time(media: CameraMedia): String =
        if (media.createdSec <= 0) "" else time.format(Instant.ofEpochSecond(media.createdSec).atOffset(ZoneOffset.UTC))
}
