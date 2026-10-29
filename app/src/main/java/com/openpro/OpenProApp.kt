package com.openpro

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.openpro.camera.CameraSession
import com.openpro.quik.QuikClient
import com.openpro.storage.Downloader
import com.openpro.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient

class OpenProApp : Application(), SingletonImageLoader.Factory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var camera: CameraSession
        private set
    lateinit var storage: Storage
        private set
    lateinit var downloader: Downloader
        private set
    lateinit var quik: QuikClient
        private set
    lateinit var batch: com.openpro.batch.BatchRepository
        private set
    val batchRunner by lazy { com.openpro.batch.BatchRunner(this) }
    val control by lazy { com.openpro.camera.remote.CameraControl(camera.ble, scope) }
    val preview by lazy { com.openpro.camera.remote.PreviewStream(camera) }
    val durations by lazy {
        com.openpro.media.ClipDurations(this, camera, scope, catalogue::onConnectedCamera) { copies.phoneUri(it, com.openpro.media.ClipVersion.Original) }
    }
    /** Every copy OpenPro has saved or uploaded, by clip. */
    lateinit var copies: com.openpro.copies.CopyIndex
        private set
    /** Every clip seen on a camera, for browsing without one. */
    lateinit var catalogue: com.openpro.copies.ClipCatalogue
        private set
    lateinit var net: com.openpro.net.NetworkMonitor
        private set
    lateinit var servers: com.openpro.server.ServerStore
        private set

    override fun onCreate() {
        super.onCreate()
        // The :quik engine process only needs QuikService; skip UI-side singletons there.
        if (getProcessName().endsWith(":quik")) return
        camera = CameraSession(this, scope)
        net = com.openpro.net.NetworkMonitor(this, camera, scope)
        storage = Storage(this)
        copies = com.openpro.copies.CopyIndex(this, storage, scope)
        catalogue = com.openpro.copies.ClipCatalogue(copies.db, scope) { camera.current?.serial }
        scope.launch(Dispatchers.IO) {
            com.openpro.media.CameraPhotoFetcher.clearAll(cacheDir)
            storage.clearPreviews()
        }
        servers = com.openpro.server.ServerStore(this)
        // An SD card or drive coming back lets jobs waiting for it continue.
        storage.volumes.onChange = { com.openpro.batch.BatchScheduler.kick(this) }
        // A preview or photo letting go of the camera lets jobs waiting for it continue.
        com.openpro.camera.http.CameraStreams.onFree = { com.openpro.batch.BatchScheduler.kick(this) }
        downloader = Downloader(storage)
        quik = QuikClient(this)
        batch = com.openpro.batch.BatchRepository(
            this, com.openpro.batch.BatchDb.open(this), com.openpro.batch.BatchSettings(this),
        )
        resumeBatchOnTriggers()
    }

    /**
     * Waiting batch jobs resume when what they wait for shows up: the camera's WiFi, or a network.
     * Also processes anything left over from a previous run.
     */
    private fun resumeBatchOnTriggers() {
        com.openpro.batch.BatchScheduler.kick(this)
        scope.launch {
            camera.state.collect { state ->
                if (state is com.openpro.camera.CameraState.Online || state is com.openpro.camera.CameraState.Ready) {
                    com.openpro.batch.BatchScheduler.kick(this@OpenProApp)
                }
            }
        }
        // Leaving the camera screens lets the batch switch to the internet for uploads.
        scope.launch {
            camera.uiHolds.collect { holds -> if (holds == 0) com.openpro.batch.BatchScheduler.kick(this@OpenProApp) }
        }
        getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = com.openpro.batch.BatchScheduler.kick(this@OpenProApp)
            },
        )
    }

    /** Thumbnails come from the camera, so Coil fetches through the camera network's client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            // Thumbnails for camera clips, with frame-grab fallbacks (media/CameraThumbnails.kt).
            add(com.openpro.media.CameraThumbFetcher.Key())
            add(com.openpro.media.CameraThumbFetcher.Factory(this@OpenProApp))
            // Full photos for the clip page (media/CameraPhotos.kt).
            add(com.openpro.media.CameraPhotoFetcher.Key())
            add(com.openpro.media.CameraPhotoFetcher.Factory(this@OpenProApp))
            add(OkHttpNetworkFetcherFactory(callFactory = {
                Call.Factory { request -> (camera.api?.client ?: fallbackClient).newCall(request) }
            }))
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("thumbnails"))
                .maxSizeBytes(64L * 1024 * 1024)
                .build()
        }
        .build()

    private val fallbackClient by lazy { OkHttpClient() }
}

val Context.app: OpenProApp get() = applicationContext as OpenProApp
