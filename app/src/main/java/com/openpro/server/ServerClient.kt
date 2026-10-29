package com.openpro.server

import com.openpro.batch.HttpStatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One file or folder on the server. */
data class DavEntry(
    val name: String, val isDirectory: Boolean, val size: Long, val etag: String?, val checksums: String?,
    /** The entry's href as the server sent it (URL-encoded, from the server's root). */
    val href: String = "",
) {
    /** The SHA1 the server reports (Nextcloud "oc:checksums" is like "SHA1:ab… MD5:cd…"), lowercase. */
    val sha1: String? get() = checksums?.split(' ')?.firstOrNull { it.startsWith("SHA1:", true) }?.substringAfter(':')?.lowercase()
}

/** The server presented a certificate Android doesn't trust; the user may choose to pin it. */
class UntrustedCertificateException(val sha256: String, val subject: String, cause: Throwable) :
    IOException("The server's certificate isn't trusted by Android ($subject)", cause)

/**
 * Nextcloud refuses requests to any address missing from its `trusted_domains` setting, with
 * HTTP 400 and an "Access through untrusted domain" page. [address] is the refused host (with its
 * port, if not the default), which is what has to be added.
 */
class UntrustedDomainException(val address: String) : HttpStatusException(
    400,
    address,
    "Nextcloud refuses the address $address because it isn't one of the server's trusted domains. " +
        "Add $address to 'trusted_domains' in the server's config/config.php (or ask its admin), " +
        "or enter an address the server already accepts.",
)

/** Throws [UntrustedDomainException] if this is Nextcloud's untrusted-domain error; otherwise returns the response. */
fun Response.rejectUntrustedDomain(): Response {
    if (code != 400) return this
    val text = runCatching { peekBody(64 * 1024).string() }.getOrDefault("")
    if (text.contains("untrusted domain", ignoreCase = true) || text.contains("trusted_domains")) {
        close()
        val url = request.url
        val default = if (url.scheme == "https") 443 else 80
        throw UntrustedDomainException(if (url.port == default) url.host else "${url.host}:${url.port}")
    }
    return this
}

/**
 * WebDAV client for one server profile, with Nextcloud's resumable chunked upload (v2) on top.
 * Remote paths are relative to the account's files root, e.g. "/OpenPro/2026-08/GX010001.MP4".
 */
class ServerClient(val profile: ServerProfile, password: String, baseClient: OkHttpClient = OkHttpClient()) {
    private val auth = Credentials.basic(profile.username, password, Charsets.UTF_8)
    private val pinning = TrustPolicy(profile.pinnedCertSha256)

    val http: OkHttpClient = pinning.applyTo(baseClient.newBuilder())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", auth).header("User-Agent", "OpenPro").build())
        }
        .build()

    private val base: HttpUrl = profile.baseUrl.trimEnd('/').toHttpUrl()
    private val user get() = profile.davUser ?: profile.username

    /** The collection all remote paths are relative to. */
    val filesRoot: HttpUrl = when (profile.type) {
        ServerType.Nextcloud -> base.newBuilder().addPathSegments("remote.php/dav/files").addPathSegment(user).build()
        ServerType.WebDav -> base
    }

    fun url(path: String): HttpUrl {
        val b = filesRoot.newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private fun collectionUrl(path: String) = url(path).toString().trimEnd('/') + "/"

    // --- basic WebDAV ---

    /** The entry at [path], or null if there is nothing there. */
    suspend fun stat(path: String): DavEntry? = propfind(url(path).toString(), depth = 0)?.firstOrNull()

    /** Children of the folder at [path] (empty if it doesn't exist). */
    suspend fun list(path: String): List<DavEntry> = propfind(collectionUrl(path), depth = 1)?.drop(1).orEmpty()

    /** Creates [path] and any missing parents. */
    suspend fun mkdirs(path: String) {
        var current = ""
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            current += "/$segment"
            mkcol(collectionUrl(current))
        }
    }

    private suspend fun mkcol(url: String, headers: Map<String, String> = emptyMap()) {
        val request = Request.Builder().url(url).method("MKCOL", null).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        execute(request) { r ->
            // 405: already exists.
            if (!r.isSuccessful && r.code != 405) throw HttpStatusException(r.code, profile.name)
        }
    }

    suspend fun delete(path: String) {
        execute(Request.Builder().url(url(path)).delete().build()) { r ->
            if (!r.isSuccessful && r.code != 404) throw HttpStatusException(r.code, profile.name)
        }
    }

    /**
     * Downloads the file at [path] to [target], carrying on from a partial download (target.part)
     * where the server allows it. Throws [java.io.FileNotFoundException] if it isn't there.
     */
    suspend fun download(path: String, target: File, onProgress: (done: Long, total: Long) -> Unit): File {
        val part = File(target.path + ".part")
        target.parentFile?.mkdirs()
        val have = part.length()
        val request = Request.Builder().url(url(path)).get().apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        execute(request) { r ->
            if (r.code == 404) throw java.io.FileNotFoundException("$path is no longer on ${profile.name}")
            if (!r.isSuccessful) throw HttpStatusException(r.code, profile.name)
            // 206: the rest of it; 200: the server sent it all again, so start over.
            val resumed = r.code == 206
            val start = if (resumed) have else 0L
            val total = start + r.body.contentLength().coerceAtLeast(0)
            java.io.FileOutputStream(part, resumed).use { out ->
                r.body.byteStream().use { input ->
                    val buffer = ByteArray(1 shl 20)
                    var done = start
                    while (true) {
                        if (job?.isActive == false) throw kotlinx.coroutines.CancellationException("cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Couldn't finish ${target.name}")
        return target
    }

    /** Uploads a whole file in one PUT (generic WebDAV; not resumable). */
    suspend fun putFile(path: String, file: File, sha1: String?, mtimeSec: Long?, length: Long = file.length(), onProgress: (Long) -> Unit) {
        put(url(path).toString(), FileRegionBody(file, 0, length, onProgress), sha1, mtimeSec)
    }

    private suspend fun put(url: String, body: RequestBody, sha1: String?, mtimeSec: Long?, extra: Map<String, String> = emptyMap()) {
        val request = Request.Builder().url(url).put(body)
            .apply {
                if (sha1 != null) header("OC-Checksum", "SHA1:$sha1")
                if (mtimeSec != null && mtimeSec > 0) header("X-OC-Mtime", mtimeSec.toString())
                extra.forEach { (k, v) -> header(k, v) }
            }
            .build()
        execute(request) { r -> if (!r.isSuccessful) throw HttpStatusException(r.code, profile.name) }
    }

    // --- Nextcloud chunked upload v2 (resumable) ---

    private fun uploadUrl(uploadId: String) =
        base.newBuilder().addPathSegments("remote.php/dav/uploads").addPathSegment(user).addPathSegment(uploadId).build()

    private fun destination(path: String) = mapOf("Destination" to url(path).toString())

    /** Starts a chunked upload session that will end up at [path]. */
    suspend fun chunkedBegin(uploadId: String, path: String) = mkcol(uploadUrl(uploadId).toString(), destination(path))

    /** Chunks already on the server, by number, with their sizes; null if the session is gone (expired). */
    suspend fun chunkedExisting(uploadId: String): Map<Int, Long>? =
        propfind(uploadUrl(uploadId).toString().trimEnd('/') + "/", depth = 1)
            ?.drop(1)
            ?.mapNotNull { e -> e.name.toIntOrNull()?.let { it to e.size } }
            ?.toMap()

    suspend fun chunkedPut(uploadId: String, index: Int, path: String, body: RequestBody, totalLength: Long) {
        val url = uploadUrl(uploadId).newBuilder().addPathSegment(index.toString()).build().toString()
        put(url, body, null, null, destination(path) + ("OC-Total-Length" to totalLength.toString()))
    }

    /** Assembles the chunks into the final file at [path]. */
    suspend fun chunkedFinish(uploadId: String, path: String, totalLength: Long, sha1: String?, mtimeSec: Long?, overwrite: Boolean) {
        val request = Request.Builder()
            .url(uploadUrl(uploadId).newBuilder().addPathSegment(".file").build())
            .method("MOVE", null)
            .header("Destination", url(path).toString())
            .header("Overwrite", if (overwrite) "T" else "F")
            .header("OC-Total-Length", totalLength.toString())
            .apply {
                if (sha1 != null) header("OC-Checksum", "SHA1:$sha1")
                if (mtimeSec != null && mtimeSec > 0) header("X-OC-Mtime", mtimeSec.toString())
            }
            .build()
        // Assembling a large file can take a while on the server.
        val slow = http.newBuilder().readTimeout(15, TimeUnit.MINUTES).build()
        execute(request, slow) { r -> if (!r.isSuccessful) throw HttpStatusException(r.code, profile.name) }
    }

    suspend fun chunkedAbort(uploadId: String) {
        runCatching { execute(Request.Builder().url(uploadUrl(uploadId)).delete().build()) {} }
    }

    // --- set-up checks ---

    /**
     * Nextcloud only: files named any of [names] anywhere under [folder] (WebDAV SEARCH), as paths
     * relative to [filesRoot] with their entries. Null when the server can't search (other WebDAV
     * servers, or a Nextcloud that refuses).
     */
    suspend fun findByName(folder: String, names: List<String>): List<Pair<String, DavEntry>>? {
        if (profile.type != ServerType.Nextcloud || names.isEmpty()) return null
        val davRoot = base.newBuilder().addPathSegments("remote.php/dav").build()
        val scope = url(folder).encodedPath.removePrefix(davRoot.encodedPath.trimEnd('/'))
        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val terms = names.joinToString("") { "<d:eq><d:prop><d:displayname/></d:prop><d:literal>${escape(it)}</d:literal></d:eq>" }
        val where = if (names.size > 1) "<d:or>$terms</d:or>" else terms
        val body = """<?xml version="1.0" encoding="UTF-8"?>
<d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:basicsearch>
    <d:select><d:prop><d:getcontentlength/><d:resourcetype/><d:getetag/><oc:checksums/></d:prop></d:select>
    <d:from><d:scope><d:href>${escape(scope)}</d:href><d:depth>infinity</d:depth></d:scope></d:from>
    <d:where>$where</d:where>
  </d:basicsearch>
</d:searchrequest>"""
        val request = Request.Builder().url(davRoot.toString().trimEnd('/') + "/")
            .method("SEARCH", body.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        val prefix = java.net.URLDecoder.decode(filesRoot.encodedPath.trimEnd('/'), "UTF-8")
        return execute(request) { r ->
            if (r.code != 207) return@execute null
            parseMultistatus(r.body.string()).mapNotNull { e ->
                val path = java.net.URLDecoder.decode(e.href, "UTF-8").substringAfter(prefix, missingDelimiterValue = "")
                path.takeIf { it.isNotEmpty() }?.let { it to e }
            }
        }
    }

    /** Nextcloud user ID for [filesRoot], which can differ from the login name. */
    suspend fun nextcloudUserId(): String? {
        val request = Request.Builder().url(base.newBuilder().addPathSegments("ocs/v2.php/cloud/user").addQueryParameter("format", "json").build())
            .header("OCS-APIRequest", "true").build()
        return execute(request) { r ->
            if (r.code == 401) throw HttpStatusException(401, profile.name)
            if (!r.isSuccessful) null else Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(r.body.string())?.groupValues?.get(1)
        }
    }

    /** Checks sign-in, creates the upload folder if needed, and writes and deletes a test file. */
    suspend fun test(): String {
        mkdirs(profile.folder)
        val probe = profile.folder.trimEnd('/') + "/.openpro-test"
        put(url(probe).toString(), "OpenPro connection test".toByteArray().toRequestBody(OCTET), null, null)
        val written = stat(probe) ?: throw IOException("Test file didn't appear on the server")
        delete(probe)
        return "Connected. Signed in as ${profile.username}; uploads go to ${profile.folder} (test file ${written.size} bytes written and removed)."
    }

    // --- plumbing ---

    private suspend fun propfind(url: String, depth: Int): List<DavEntry>? {
        val request = Request.Builder().url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth.toString())
            .build()
        return execute(request) { r ->
            when {
                r.code == 404 -> null
                r.code != 207 && !r.isSuccessful -> throw HttpStatusException(r.code, profile.name)
                else -> parseMultistatus(r.body.string())
            }
        }
    }

    /**
     * Sends [request] and hands the response to [read], closing it afterwards. Both happen off the
     * main thread, since reading (or discarding) a body is network I/O too; every public function
     * here is safe to call from the UI.
     */
    private suspend fun <T> execute(request: Request, client: OkHttpClient = http, read: (Response) -> T): T =
        withContext(Dispatchers.IO) { pinning.explain { client.newCall(request).await() }.rejectUntrustedDomain().use(read) }

    /** Streams part of a file, reporting bytes sent. */
    /** Streams part of a file, reporting bytes sent. */
    class FileRegionBody(private val file: File, private val offset: Long, private val length: Long, private val onProgress: (Long) -> Unit) : RequestBody() {
        override fun contentType(): MediaType = OCTET
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(256 * 1024)
                var left = length
                var sent = 0L
                while (left > 0) {
                    val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("${file.name} is shorter than expected")
                    sink.write(buffer, 0, n)
                    left -= n
                    sent += n
                    onProgress(sent)
                }
            }
        }
    }

    companion object {
        val OCTET = "application/octet-stream".toMediaType()

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/><oc:checksums/><oc:size/></d:prop>
</d:propfind>"""

        fun sha256(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

        /** SHA1 of a file, hex lowercase, reporting progress. */
        fun sha1(file: File, onProgress: (Long) -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    done += n
                    onProgress(done)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Parses a WebDAV 207 multistatus; the first entry is the requested resource itself. */
        fun parseMultistatus(xml: String): List<DavEntry> {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            return (0 until responses.length).map { i ->
                val r = responses.item(i) as Element
                fun text(ns: String, tag: String) = r.getElementsByTagNameNS(ns, tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
                val href = text("DAV:", "href").orEmpty()
                val name = java.net.URLDecoder.decode(href.trimEnd('/').substringAfterLast('/'), "UTF-8")
                val isDir = r.getElementsByTagNameNS("DAV:", "collection").length > 0
                val size = text("DAV:", "getcontentlength")?.toLongOrNull() ?: text("http://owncloud.org/ns", "size")?.toLongOrNull() ?: -1
                val checksums = r.getElementsByTagNameNS("http://owncloud.org/ns", "checksum").let { list ->
                    (0 until list.length).joinToString(" ") { list.item(it).textContent.trim() }.takeIf { it.isNotBlank() }
                }
                DavEntry(name, isDir, size, text("DAV:", "getetag"), checksums, href)
            }
        }
    }
}

/** Runs the call on OkHttp's threads and cancels it if the coroutine is cancelled. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}

/**
 * TLS for user servers: trusts what Android trusts (including user-installed CAs), plus one
 * certificate the user pinned (self-signed servers). Remembers the certificate it rejected so the
 * UI can show its fingerprint and offer to pin it.
 */
class TrustPolicy(private val pinned: String?) : X509TrustManager {
    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()
    @Volatile private var lastRejected: Pair<String, String>? = null

    fun pinnedMatches(cert: X509Certificate?) = pinned != null && cert != null && ServerClient.sha256(cert) == pinned

    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@TrustPolicy), SecureRandom()) }
        return builder.sslSocketFactory(ssl.socketFactory, this)
            // A pinned self-signed certificate often doesn't name the host (e.g. a LAN IP); the pin is the check.
            .hostnameVerifier { host, session ->
                pinnedMatches(session.peerCertificates.firstOrNull() as? X509Certificate) ||
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
            }
    }

    /** Runs [block], turning a rejected certificate into [UntrustedCertificateException]. */
    inline fun <T> explain(block: () -> T): T = try {
        clearRejected()
        block()
    } catch (e: IOException) {
        rejected()?.let { (sha, subject) -> throw UntrustedCertificateException(sha, subject, e) }
        throw e
    }

    fun rejected(): Pair<String, String>? = lastRejected

    fun clearRejected() {
        lastRejected = null
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            val leaf = chain.first()
            if (pinnedMatches(leaf)) return
            lastRejected = ServerClient.sha256(leaf) to leaf.subjectX500Principal.name
            throw e
        }
    }
}
