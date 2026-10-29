package com.openpro.quik

import android.util.Log
import com.gopro.quikengine.model.NotFoundError
import com.gopro.quikengine.model.assetstore.AssetStoreResult
import com.gopro.quikengine.model.assetstore.Content
import com.gopro.quikengine.model.assetstore.Identifier
import com.gopro.quikengine.model.assetstore.Label
import com.gopro.quikengine.model.assetstore.LabelData
import com.gopro.quikengine.model.assetstore.Location
import com.gopro.quikengine.model.assetstore.Media
import com.gopro.quikengine.model.assetstore.Provider
import com.gopro.quikengine.model.assetstore.Quality
import com.gopro.quikengine.model.assetstore.Shot
import com.gopro.quikengine.model.assetstore.Source
import com.gopro.quikengine.model.assetstore.Type
import com.gopro.quikengine.services.AssetStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Answers the engine's media lookups. Shot shapes copy Quik's AssetStore (smarty...assetStore.d):
 *  - camera clip: remote Source (Original) with a remote EditProxy video (Editing) and a remote
 *    GPMF telemetry track (None) as resources;
 *  - local file: local Source (Original).
 * Resources the engine derives (analysis caches, thumbnails) are registered back here and
 * persisted, so the engine's caches keep working across runs.
 */
class HeroAssetStore(private val registryFile: File) : AssetStore.Api {
    private data class Entry(val url: String, val location: Location)

    @Serializable
    private data class Registered(
        val token: String,
        val url: String,
        val content: String,
        val label: String,
        val quality: String,
        val location: String,
        val taskName: String? = null,
    )

    @Serializable
    private data class Registry(
        val byParentToken: MutableMap<String, MutableList<Registered>> = mutableMapOf(),
        val byShotKey: MutableMap<String, MutableList<Registered>> = mutableMapOf(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val tokens = mutableMapOf<String, Entry>()
    private val shots = mutableMapOf<String, () -> Shot>()
    private val registry: Registry = load()

    @Synchronized
    fun putCameraClip(key: String, source: CameraSource, proxyOnly: Boolean) {
        val src = token("cam-src", key)
        val lrv = token("cam-lrv", key)
        val gpmf = token("cam-gpmf", key)
        tokens[src] = Entry(if (proxyOnly && source.lowResUrl != null) source.lowResUrl else source.sourceUrl, Location.Remote)
        source.lowResUrl?.let { tokens[lrv] = Entry(it, Location.Remote) }
        tokens[gpmf] = Entry(source.telemetryUrl, Location.Remote)
        shots[key] = {
            val resources = buildList {
                if (source.lowResUrl != null && !proxyOnly) {
                    add(media(Content.Video, Label.Proxy, Quality.Editing, Location.Remote, lrv))
                }
                add(media(Content.GPMF, Label.Proxy, Quality.None, Location.Remote, gpmf))
                addAll(registeredFor(src))
            }
            Shot(
                Type.Video,
                arrayOf(Media(Content.Video, Label.Source, Quality.Original, arrayOf(Location.Remote), src,
                    resources.toTypedArray(), LabelData.None.INSTANCE)),
                shotResources(key),
                true,
            )
        }
    }

    @Synchronized
    fun putLocalFile(key: String, path: String) {
        val src = token("file", key)
        tokens[src] = Entry(path, Location.Local)
        shots[key] = {
            Shot(
                Type.Video,
                arrayOf(Media(Content.Video, Label.Source, Quality.Original, arrayOf(Location.Local), src,
                    registeredFor(src).toTypedArray(), LabelData.None.INSTANCE)),
                shotResources(key),
                false,
            )
        }
    }

    private fun token(kind: String, key: String) = "openpro::$kind::$key"

    private fun media(content: Content, label: Label, quality: Quality, location: Location, token: String) =
        Media(content, label, quality, arrayOf(location), token, emptyArray(), LabelData.None.INSTANCE)

    private fun registeredFor(parentToken: String): List<Media> =
        registry.byParentToken[parentToken].orEmpty().filter { File(it.url).exists() || !it.url.startsWith("/") }.map { it.toMedia() }

    private fun shotResources(key: String): Array<Media> =
        registry.byShotKey[key].orEmpty().map { it.toMedia() }.toTypedArray()

    private fun Registered.toMedia() = Media(
        Content.valueOf(content), Label.valueOf(label), Quality.valueOf(quality), arrayOf(Location.valueOf(location)),
        token, emptyArray(), taskName?.let { LabelData.TaskData(it) } ?: LabelData.None.INSTANCE,
    )

    private fun register(newMedia: Media, newSource: Source): Registered {
        val token = "openpro::reg::" + UUID.randomUUID()
        val location = newMedia.locations.firstOrNull() ?: Location.Local
        tokens[token] = Entry(newSource.url, location)
        return Registered(
            token, newSource.url, newMedia.content.name, newMedia.label.name, newMedia.quality.name, location.name,
            (newMedia.labelData as? LabelData.TaskData)?.name,
        )
    }

    @Synchronized
    override fun registerResourceForMedia(parent: Media, newMedia: Media, newSource: Source): Media {
        val entry = register(newMedia, newSource)
        registry.byParentToken.getOrPut(parent.token) { mutableListOf() }.add(entry)
        save()
        return entry.toMedia()
    }

    @Synchronized
    override fun registerResourceForShot(identifier: Identifier, newMedia: Media, newSource: Source): Shot? {
        val entry = register(newMedia, newSource)
        registry.byShotKey.getOrPut(identifier.key) { mutableListOf() }.add(entry)
        save()
        return shots[identifier.key]?.invoke()
    }

    @Synchronized
    override fun resolveIdentifier(identifier: Identifier): Shot? {
        val shot = shots[identifier.key]?.invoke()
        Log.d(TAG, "resolveIdentifier $identifier -> ${shot != null}")
        return shot
    }

    @Synchronized
    override fun resolveSource(source: Source): Identifier {
        val known = tokens.entries.firstOrNull { it.value.url == source.url }?.key
        val key = known?.substringAfterLast("::")
        return if (key != null && shots.containsKey(key)) Identifier(Provider.PlatformURI, key)
        else Identifier(Provider.File, source.url)
    }

    @Synchronized
    override fun resolveToken(token: String, location: Location): AssetStoreResult {
        val entry = tokens[token] ?: registry.let { reg ->
            (reg.byParentToken.values.flatten() + reg.byShotKey.values.flatten())
                .firstOrNull { it.token == token }
                ?.let { Entry(it.url, Location.valueOf(it.location)) }
        }
        Log.d(TAG, "resolveToken $token ($location) -> ${entry?.url}")
        return if (entry == null) AssetStoreResult.Fail(NotFoundError("unknown token $token", ""))
        else AssetStoreResult.Success(Source(entry.url, HashMap()))
    }

    private fun load(): Registry = runCatching {
        json.decodeFromString(Registry.serializer(), registryFile.readText())
    }.getOrElse { Registry() }

    private fun save() {
        runCatching {
            registryFile.parentFile?.mkdirs()
            registryFile.writeText(json.encodeToString(Registry.serializer(), registry))
        }.onFailure { Log.w(TAG, "saving asset registry", it) }
    }

    companion object {
        private const val TAG = "HeroAssetStore"
    }
}
