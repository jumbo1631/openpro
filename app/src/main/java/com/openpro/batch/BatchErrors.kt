package com.openpro.batch

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.min

/** An HTTP error from the camera or a server, with the status code kept for classification. */
open class HttpStatusException(val code: Int, val where: String, message: String? = null) :
    IOException(message ?: "$where returned HTTP $code")

/** A precondition that isn't met yet; the job waits (no attempt is used up). */
class WaitException(val reason: String) : Exception(reason)

/** A failure that retrying won't fix. */
class PermanentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A failure worth retrying with backoff (engine hiccup, interrupted transfer, ...). */
class TransientException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class Outcome { Retry, Fail, Wait }

object BatchErrors {
    const val MAX_ATTEMPTS = 5

    fun classify(t: Throwable): Outcome = when (t) {
        is WaitException -> Outcome.Wait
        is com.openpro.storage.StorageUnavailableException -> Outcome.Wait
        is com.openpro.server.UntrustedCertificateException -> Outcome.Fail
        is IOException if t.isNoSpace() -> Outcome.Wait
        is PermanentException -> Outcome.Fail
        is TransientException -> Outcome.Retry
        is HttpStatusException -> when (t.code) {
            401, 403 -> Outcome.Fail // credentials or permissions: retrying won't help
            404, 410 -> Outcome.Fail // file gone from the camera / folder missing on server
            507, 413 -> Outcome.Fail // server full / file too large for it
            408, 425, 429 -> Outcome.Retry
            in 500..599 -> Outcome.Retry
            else -> Outcome.Fail
        }
        is SocketTimeoutException, is ConnectException, is NoRouteToHostException,
        is UnknownHostException, is SocketException -> Outcome.Retry
        is IOException -> Outcome.Retry
        else -> Outcome.Fail
    }

    /** The other end couldn't be reached at all (as opposed to failing partway, or answering with an error). */
    fun isUnreachable(t: Throwable) = generateSequence(t) { it.cause }.any {
        it is SocketTimeoutException || it is ConnectException || it is NoRouteToHostException || it is UnknownHostException
    }

    private fun Throwable.isNoSpace() =
        generateSequence(this) { it.cause }.any { "ENOSPC" in (it.message ?: "") || "No space left" in (it.message ?: "") }

    /** 30 s, 1 min, 2 min, 4 min, ... capped at 15 min. */
    fun backoffMs(attempt: Int): Long = min(30_000L shl (attempt - 1).coerceIn(0, 10), 15 * 60_000L)

    /** A short, human message for the batch page. */
    fun describe(t: Throwable): String = when (t) {
        is HttpStatusException -> when (t.code) {
            401 -> "${t.where}: sign-in rejected. Check the server account in Settings."
            403 -> "${t.where}: permission denied for this folder."
            404 -> "${t.where}: file or folder not found."
            507 -> "${t.where}: not enough space on the server."
            else -> t.message ?: "HTTP ${t.code}"
        }
        is com.openpro.server.UntrustedCertificateException ->
            "The server's certificate isn't trusted (it may have changed). Open Servers and test the connection to review it."
        is SocketTimeoutException -> "Connection timed out"
        is ConnectException, is NoRouteToHostException -> "Couldn't connect (${t.message})"
        is UnknownHostException -> "Server address not found (${t.message})"
        is IOException if t.isNoSpace() -> "The storage filled up; free some space (Storage page) and it resumes"
        else -> t.message ?: t.javaClass.simpleName
    }
}
