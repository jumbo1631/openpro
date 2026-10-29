package com.openpro.server

import com.openpro.batch.HttpStatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Nextcloud Login Flow v2: OpenPro asks the server for a login session, the user signs in in
 * their browser (with 2FA, SSO, whatever the server uses) and grants access, and OpenPro receives
 * an app password it can revoke from Nextcloud's security settings. OpenPro never sees the
 * user's real password.
 */
class NextcloudLogin(private val trust: TrustPolicy = TrustPolicy(null)) {
    private val http: OkHttpClient = trust.applyTo(OkHttpClient.Builder()).build()

    data class Session(val loginUrl: String, val pollEndpoint: String, val token: String)
    data class Result(val server: String, val loginName: String, val appPassword: String)

    // Both functions read response bodies, which is network I/O, so they run on Dispatchers.IO and
    // are safe to call from the UI.

    suspend fun start(serverUrl: String): Session = withContext(Dispatchers.IO) {
        val base = serverUrl.trimEnd('/').toHttpUrl()
        val url = base.newBuilder().addPathSegments("index.php/login/v2").build()
        val request = Request.Builder().url(url).post(FormBody.Builder().build()).header("User-Agent", "OpenPro").build()
        val body = trust.explain { http.newCall(request).await() }.use { r ->
            r.rejectUntrustedDomain()
            if (!r.isSuccessful) throw HttpStatusException(r.code, "Nextcloud")
            r.body.string()
        }
        val root = Json.parseToJsonElement(body).jsonObject
        val poll = root["poll"]?.jsonObject ?: throw IOException("This doesn't look like a Nextcloud server")
        val login = root["login"]!!.jsonPrimitive.content
        val endpoint = poll["endpoint"]!!.jsonPrimitive.content
        Session(loginUrl = onto(base, login), pollEndpoint = onto(base, endpoint), token = poll["token"]!!.jsonPrimitive.content)
    }

    /**
     * Polls until the user has granted access in the browser (or the 20-minute session expires).
     * Anything sent to [wake] makes it check at once, e.g. when the user comes back to the app.
     *
     * A poll that can't reach the server goes to [keepWaiting]; returning true carries on. While
     * the user is in the browser the app is in the background, and Android (15 and later) cuts
     * background apps off the network, so those failures are expected and mean nothing.
     */
    suspend fun await(session: Session, wake: ReceiveChannel<Unit>? = null, keepWaiting: (IOException) -> Boolean = { false }): Result =
        withContext(Dispatchers.IO) { poll(session, wake, keepWaiting) }

    private suspend fun poll(session: Session, wake: ReceiveChannel<Unit>?, keepWaiting: (IOException) -> Boolean): Result {
        val deadline = System.currentTimeMillis() + 20 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            val request = Request.Builder().url(session.pollEndpoint)
                .post(FormBody.Builder().add("token", session.token).build())
                .build()
            val result = try {
                trust.explain { http.newCall(request).await() }.use { r ->
                    r.rejectUntrustedDomain()
                    when {
                        r.code == 404 -> null // not granted yet
                        r.isSuccessful -> Json.parseToJsonElement(r.body.string()).jsonObject.let { o ->
                            Result(
                                server = o["server"]!!.jsonPrimitive.content,
                                loginName = o["loginName"]!!.jsonPrimitive.content,
                                appPassword = o["appPassword"]!!.jsonPrimitive.content,
                            )
                        }
                        else -> throw HttpStatusException(r.code, "Nextcloud")
                    }
                }
            } catch (e: IOException) {
                // The server's own answers (HTTP errors, certificate) are final; only failing to reach it may pass.
                if (e is HttpStatusException || e is UntrustedCertificateException || !keepWaiting(e)) throw e
                null
            }
            if (result != null) return result
            withTimeoutOrNull(2_000) { wake?.receive() ?: awaitCancellation() }
        }
        throw IOException("Sign-in wasn't completed in time; start again")
    }

    companion object {
        /**
         * [url] with the scheme, host and port of [base], the address the user entered. Nextcloud
         * builds its login and poll URLs from its own idea of its address, which behind a proxy can
         * be an internal name, a different port, or http for https; the user's address is the one
         * known to reach it from this phone.
         */
        fun onto(base: HttpUrl, url: String): String {
            val u = url.toHttpUrlOrNull() ?: return url
            return u.newBuilder().scheme(base.scheme).host(base.host).port(base.port).build().toString()
        }
    }
}
