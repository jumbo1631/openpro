package com.openpro.quik

import android.content.Context
import android.net.Uri
import com.gopro.quikengine.EngineLocation
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * GoPro's stabilization engine, copied on the phone into the app's own storage: out of GoPro Quik
 * installed on the phone, out of Quik downloaded from APKMirror, or out of a Quik file the user
 * picked (the .apkm bundle from APKMirror, an .xapk/.apks, or a single APK). Nothing of GoPro's is in the app itself, so the published app
 * can offer stabilization to anyone who supplies Quik 13.43.
 */
object EngineFiles {
    const val QUIK_PACKAGE = "com.gopro.smarty"
    const val QUIK_VERSION = "13.43"
    const val QUIK_VERSION_CODE = 27143L
    /** The one 13.43 download on APKMirror: the arm64 bundle. */
    const val APKMIRROR_URL = "https://www.apkmirror.com/apk/gopro-rm/gopro-formerly-capture-featuring-quikstories/" +
        "gopro-quik-video-editor-13-43-release/gopro-quik-video-editor-13-43-android-apk-download/"

    /** Quik 13.43 (version code 27143): the engine OpenPro's code is built against. */
    private val EXPECTED_SHA256 = mapOf(
        "lib/arm64-v8a/libnative-lib.so" to "5610d6290b1d935ae67af707fbb3302258037af444e963258d9b814f21124dcd",
        "assets/qe_bundle/lib/python.zip" to "f6b8718eca631e6dd019e937cc85de8033dce3573dea8a202509ed0433842202",
    )
    private val LIBS = listOf(
        "libnative-lib.so", "libavutil.so", "libavcodec.so", "libavformat.so", "libavdevice.so",
        "libswresample.so", "libswscale.so", "libavfilter.so", "libfribidi.so", "libc++_shared.so",
    )
    private const val LIB_PREFIX = "lib/arm64-v8a/"
    private const val BUNDLE_PREFIX = "assets/qe_bundle/"
    /** The Qualcomm accelerator: only for ML features the stabilizer doesn't use (~90 MB). */
    private const val QNN_PREFIX = "assets/qe_bundle/lib/quik-qualcomm_tflite_delegate/"

    class WrongFile(message: String) : IOException(message)

    private fun dir(context: Context) = File(context.filesDir, "engine")
    private fun libDir(context: Context) = File(dir(context), "lib")
    private fun bundleDir(context: Context) = File(dir(context), "qe_bundle")
    private fun marker(context: Context) = File(dir(context), "installed")

    /** Whether the engine has been added (from a picked Quik file). */
    fun available(context: Context): Boolean = marker(context).exists() && File(libDir(context), "libnative-lib.so").exists()

    /** Points the engine at its copy in the app's storage. Call before using the engine. */
    fun prepare(context: Context) {
        if (!available(context)) return
        EngineLocation.libDir = libDir(context)
        EngineLocation.bundleDir = bundleDir(context)
    }

    fun remove(context: Context) {
        dir(context).deleteRecursively()
    }

    /** GoPro Quik on this phone, if any: whether it's the version the engine can be taken from. */
    sealed interface InstalledQuik {
        data object None : InstalledQuik
        data class WrongVersion(val version: String) : InstalledQuik
        data object Usable : InstalledQuik
    }

    fun installedQuik(context: Context): InstalledQuik {
        val info = runCatching { context.packageManager.getPackageInfo(QUIK_PACKAGE, 0) }.getOrNull()
            ?: return InstalledQuik.None
        return if (info.longVersionCode == QUIK_VERSION_CODE) InstalledQuik.Usable
        else InstalledQuik.WrongVersion(info.versionName ?: info.longVersionCode.toString())
    }

    /** Copies the engine out of GoPro Quik installed on this phone (its APK files are readable by any app). */
    fun installFromPhone(context: Context, progress: (String) -> Unit) {
        if (installedQuik(context) != InstalledQuik.Usable) {
            throw WrongFile("GoPro Quik $QUIK_VERSION isn't installed on this phone.")
        }
        val app = context.packageManager.getApplicationInfo(QUIK_PACKAGE, 0)
        val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
        install(context, progress) { _ -> paths.map(::ZipFile) }
    }

    /** Copies the engine out of the picked file. */
    fun install(context: Context, uri: Uri, progress: (String) -> Unit) {
        install(context, progress) { work ->
            progress("Reading the file…")
            val picked = File(work, "picked")
            (context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open that file")).use { input ->
                picked.outputStream().use { input.copyTo(it, 1 shl 20) }
            }
            openApks(picked, work, progress)
        }
    }

    /**
     * Downloads Quik from [url] (a download started on APKMirror's page inside OpenPro, with that
     * page's [headers]: cookies, user agent, referrer) and copies the engine out of it.
     * [cancelled] is checked as it goes; once true, it stops with a [kotlin.coroutines.cancellation.CancellationException].
     */
    fun installDownload(context: Context, url: String, headers: Map<String, String>, progress: (String) -> Unit, cancelled: () -> Boolean) {
        install(context, progress) { work ->
            progress("Downloading GoPro Quik…")
            val picked = File(work, "picked")
            val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            OkHttpClient().newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw IOException("The download didn't work (error ${r.code}). Try again, or pick the file instead.")
                val total = r.body.contentLength()
                r.body.byteStream().use { input ->
                    picked.outputStream().use { out ->
                        val buffer = ByteArray(1 shl 16)
                        var done = 0L
                        var shownMb = -1L
                        while (true) {
                            if (cancelled()) throw kotlin.coroutines.cancellation.CancellationException()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val mb = done shr 20
                            if (mb != shownMb) {
                                shownMb = mb
                                progress(if (total > 0) "Downloading GoPro Quik… $mb of ${total shr 20} MB" else "Downloading GoPro Quik… $mb MB")
                            }
                        }
                    }
                }
            }
            openApks(picked, work, progress)
        }
    }

    /**
     * Copies the engine out of the APKs [open] gives (in a scratch folder it may use). Refuses
     * anything but Quik 13.43 (checked by the engine's own fingerprints). Replaces any engine added
     * before only once the new one is complete.
     */
    private fun install(context: Context, progress: (String) -> Unit, open: (work: File) -> List<ZipFile>) {
        val work = File(context.cacheDir, "engine-import").apply { deleteRecursively(); mkdirs() }
        val staging = File(context.filesDir, "engine-new").apply { deleteRecursively() }
        try {
            val apks = open(work)
            try {
                progress("Checking it's GoPro Quik 13.43…")
                for ((member, expected) in EXPECTED_SHA256) {
                    val zip = apks.firstOrNull { it.getEntry(member) != null }
                        ?: throw WrongFile("This doesn't look like GoPro Quik: it has no stabilization engine in it.")
                    if (sha256(zip, member) != expected) {
                        throw WrongFile("This is a different version of GoPro Quik. OpenPro needs exactly Quik $QUIK_VERSION (version code $QUIK_VERSION_CODE).")
                    }
                }
                progress("Copying the engine…")
                val libs = File(staging, "lib").apply { mkdirs() }
                for (name in LIBS) {
                    val zip = apks.firstOrNull { it.getEntry(LIB_PREFIX + name) != null } ?: continue
                    copy(zip, LIB_PREFIX + name, File(libs, name))
                }
                val bundle = File(staging, "qe_bundle").apply { mkdirs() }
                for (zip in apks) {
                    for (entry in zip.entries()) {
                        if (entry.isDirectory || !entry.name.startsWith(BUNDLE_PREFIX) || entry.name.startsWith(QNN_PREFIX)) continue
                        val out = File(bundle, entry.name.removePrefix(BUNDLE_PREFIX))
                        // Entries name paths inside the bundle; refuse any that would land outside it.
                        if (!out.canonicalPath.startsWith(bundle.canonicalPath + File.separator)) continue
                        out.parentFile?.mkdirs()
                        copy(zip, entry.name, out)
                    }
                }
                File(staging, "installed").writeText("GoPro Quik 13.43 (27143)\n")
            } finally {
                apks.forEach { runCatching { it.close() } }
            }
            dir(context).deleteRecursively()
            if (!staging.renameTo(dir(context))) throw IOException("Couldn't put the engine in place")
            progress("Done")
        } finally {
            work.deleteRecursively()
            staging.deleteRecursively()
        }
    }

    /**
     * The APKs to look in: the picked file itself when it's an APK, or the ones inside it when it's
     * a bundle (only the base and the arm64 split, which hold the engine, are unpacked).
     */
    private fun openApks(picked: File, work: File, progress: (String) -> Unit): List<ZipFile> {
        val outer = runCatching { ZipFile(picked) }.getOrElse { throw WrongFile("That isn't an APK or an app bundle.") }
        val inner = outer.entries().toList().filter { !it.isDirectory && it.name.endsWith(".apk") }
        if (inner.isEmpty()) return listOf(outer)
        val wanted = inner.filter { val n = it.name.substringAfterLast('/'); n == "base.apk" || "arm64" in n || n.startsWith("com.gopro") }
            .ifEmpty { inner }
        progress("Unpacking the bundle…")
        val apks = wanted.mapIndexed { i, entry ->
            val file = File(work, "apk$i.apk")
            outer.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it, 1 shl 20) } }
            ZipFile(file)
        }
        outer.close()
        return apks
    }

    private fun copy(zip: ZipFile, member: String, to: File) {
        zip.getInputStream(zip.getEntry(member)).use { input -> to.outputStream().use { input.copyTo(it, 1 shl 20) } }
    }

    private fun sha256(zip: ZipFile, member: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.getInputStream(zip.getEntry(member)).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
